#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usb/ch9.h>
#include <linux/usb/functionfs.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define PACKED __attribute__((packed))
struct PACKED group {
  struct usb_interface_descriptor intf;
  struct usb_endpoint_descriptor_no_audio in, out;
};
struct PACKED descs {
  struct usb_functionfs_descs_head_v2 h;
  uint32_t fs_count, hs_count;
  struct group fs, hs;
};
static int s, epin, epout;
static void die(const char *str) {
  perror(str);
  exit(1);
}
static void exact_send(int fd, void *b, size_t n) {
  char *p = b;
  while (n) {
    ssize_t r = write(fd, p, n);
    if (r < 1)
      die("packet write");
    n -= r;
    p += r;
  }
}
static void exact_recv(int fd, void *b, size_t n) {
  char *p = b;
  while (n) {
    ssize_t r = read(fd, p, n);
    if (r < 1)
      die("packet read");
    n -= r;
    p += r;
  }
}
static void *return_usb(void *unused) {
  unsigned char *payload = malloc(1048576);
  while (1) {
    uint32_t header[6];
    exact_recv(s, header, 24);
    if (header[3] > 1048576)
      exit(2);
    exact_send(epin, header, 24);
    if (header[3]) {
      exact_recv(s, payload, header[3]);
      exact_send(epin, payload, header[3]);
    }
  }
  return NULL;
}
int main(int argc, char **argv) {
  if (argc != 2)
    return 2;
  struct descs config = {
      .h = {.magic = FUNCTIONFS_DESCRIPTORS_MAGIC_V2,
            .length = sizeof(config),
            .flags = FUNCTIONFS_HAS_FS_DESC | FUNCTIONFS_HAS_HS_DESC},
      .fs_count = 3,
      .hs_count = 3};
  struct group g = {.intf = {.bLength = 9,
                             .bDescriptorType = USB_DT_INTERFACE,
                             .bNumEndpoints = 2,
                             .bInterfaceClass = 255,
                             .bInterfaceSubClass = 0x42,
                             .bInterfaceProtocol = 1,
                             .iInterface = 1},
                    .in = {.bLength = 7,
                           .bDescriptorType = USB_DT_ENDPOINT,
                           .bEndpointAddress = 0x81,
                           .bmAttributes = USB_ENDPOINT_XFER_BULK,
                           .wMaxPacketSize = 64},
                    .out = {.bLength = 7,
                            .bDescriptorType = USB_DT_ENDPOINT,
                            .bEndpointAddress = 0x02,
                            .bmAttributes = USB_ENDPOINT_XFER_BULK,
                            .wMaxPacketSize = 64}};
  config.fs = g;
  g.in.wMaxPacketSize = 512;
  g.out.wMaxPacketSize = 512;
  config.hs = g;
  int fd = open("ep0", O_RDWR);
  if (fd < 0)
    die("open ep0");
  if (write(fd, &config, sizeof(config)) != sizeof(config))
    die("descriptor write");
  struct PACKED {
    struct usb_functionfs_strings_head h;
    uint16_t lang;
    char name[4];
  } strings = {.h = {.magic = FUNCTIONFS_STRINGS_MAGIC,
                     .length = sizeof(strings),
                     .str_count = 1,
                     .lang_count = 1},
               .lang = 0x409,
               .name = "ADB"};
  if (write(fd, &strings, sizeof(strings)) != sizeof(strings))
    die("strings write");
  epin = open("ep1", O_RDWR);
  epout = open("ep2", O_RDWR);
  if (epin < 0 || epout < 0)
    die("bulk open");
  fprintf(stderr, "FUNCTIONFS READY\n");
  for (;;) {
    struct usb_functionfs_event ev;
    exact_recv(fd, &ev, sizeof(ev));
    if (ev.type == FUNCTIONFS_ENABLE)
      break;
  }
  fprintf(stderr, "USB ENABLED\n");
  int port = atoi(argv[1]);
  s = socket(AF_INET, SOCK_STREAM, 0);
  struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(port)};
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (connect(s, (void *)&addr, sizeof(addr)))
    die("adbd connect");
  pthread_t th;
  pthread_create(&th, NULL, return_usb, NULL);
  unsigned char buffer[16384];
  for (;;) {
    ssize_t n = read(epout, buffer, sizeof(buffer));
    if (n < 0)
      die("USB OUT");
    if (n > 0)
      exact_send(s, buffer, n);
  }
}
