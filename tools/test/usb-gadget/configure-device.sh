#!/system/bin/sh
set -e
base=/config/usb_gadget/g1
cd "$base"
echo 0x18d1 > idVendor
echo 0x4ee7 > idProduct
echo 0x0200 > bcdUSB
mkdir -p strings/0x409 configs/c.1/strings/0x409 functions/ffs.debug
echo OnDeviceTest > strings/0x409/manufacturer
echo 'Developer phone' > strings/0x409/product
echo TARGET5594 > strings/0x409/serialnumber
echo ADB > configs/c.1/strings/0x409/configuration
ln -s /config/usb_gadget/g1/functions/ffs.debug /config/usb_gadget/g1/configs/c.1/f1
mkdir -p /data/local/tmp/studio-ffs
mount -t functionfs debug /data/local/tmp/studio-ffs
