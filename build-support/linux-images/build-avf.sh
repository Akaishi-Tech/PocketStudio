#!/bin/bash
# Native GitHub Actions runner, already inside private PID and mount namespaces.
set -euo pipefail
image=$1
cache=$2
recipe=$3
sdk=$4
ca=$5
work=$(mktemp -d)
root=$work/root
mkdir "$root"
mount --make-rprivate /
cleanup() {
  set +e
  if mountpoint -q "$root"; then umount -R "$root"; fi
  rmdir "$root" "$work"
}
trap cleanup EXIT
check_ext4() {
  local rc=0
  e2fsck -pf "$image" || rc=$?
  (( rc <= 1 ))
}
check_ext4
truncate -s 8G "$image"
resize2fs "$image"
mount -t ext4 -o loop "$image" "$root"
grep -q '^VERSION_CODENAME=trixie' "$root/etc/os-release"
mkdir -p "$root"/{dev,proc,run,recipe,cache}
mount -t tmpfs -o mode=0755 tmpfs "$root/dev"
for dev in null zero random urandom tty; do
  touch "$root/dev/$dev"
  mount --bind "/dev/$dev" "$root/dev/$dev"
done
mkdir "$root/dev/pts"
mount -t devpts -o newinstance,ptmxmode=0666 devpts "$root/dev/pts"
ln -s pts/ptmx "$root/dev/ptmx"
mount -t proc proc "$root/proc"
mount -t tmpfs tmpfs "$root/run"
mount --bind "$recipe" "$root/recipe"
mount --bind "$cache" "$root/cache"
touch "$root/build-ca.crt"
mount --bind "$ca" "$root/build-ca.crt"
# The donor's resolver is usually a systemd-managed symlink; restore it verbatim.
if [ -e "$root/etc/resolv.conf" ] || [ -L "$root/etc/resolv.conf" ]; then
  mv "$root/etc/resolv.conf" "$root/etc/resolv.conf.studio-original"
fi
cp -L /etc/resolv.conf "$root/etc/resolv.conf"
chroot "$root" /usr/bin/env SDK_ARCHIVE="$sdk" /bin/sh /recipe/prepare-debian.sh avf
rm "$root/etc/resolv.conf"
if [ -e "$root/etc/resolv.conf.studio-original" ] || [ -L "$root/etc/resolv.conf.studio-original" ]; then
  mv "$root/etc/resolv.conf.studio-original" "$root/etc/resolv.conf"
fi
install -d -m 0700 -o 1100 -g 1100 "$root/home/studio/.ssh"
echo ferrochrome-4000000 > "$root/etc/pocket-studio/image-version"
test -x "$root/usr/lib/systemd/systemd"
test -L "$root/etc/systemd/system/multi-user.target.wants/studio-ssh.service"
test -f "$root/etc/fstab"
umount "$root"/{build-ca.crt,cache,recipe,run,proc}
umount -R "$root/dev"
rm "$root/build-ca.crt"
rmdir "$root/cache" "$root/recipe"
sync -f "$root"
fstrim -m 1M "$root" || true
umount "$root"
check_ext4
