#!/bin/sh
# Runs only inside the target Debian filesystem on the Actions runner.
set -eu
export DEBIAN_FRONTEND=noninteractive LC_ALL=C
variant=$1
. /etc/os-release
[ "$VERSION_CODENAME" = trixie ]
# Preserve the official guest's package sources, including any AVF-specific sources.
mkdir /tmp/studio-sources
cp -a /etc/apt/sources.list.d /tmp/studio-sources/
if [ -e /etc/apt/sources.list ]; then cp /etc/apt/sources.list /tmp/studio-sources/; fi
rm -f /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources
for archive in debian debian-security; do
  suite=trixie
  if [ "$archive" = debian-security ]; then suite=trixie-security; fi
  printf 'deb [check-valid-until=no signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://snapshot.debian.org/archive/%s/20261118T000000Z %s main\n' "$archive" "$suite"
done > /etc/apt/sources.list
printf 'Acquire::https::CaInfo "/build-ca.crt";\nAcquire::Retries "4";\n' > /etc/apt/apt.conf.d/98-image-build
# No package postinst may start a daemon in the builder.
if [ -e /usr/sbin/policy-rc.d ]; then cp -a /usr/sbin/policy-rc.d /tmp/studio-sources/; fi
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
apt-get update
set -- bash ca-certificates git curl less nano file unzip tzdata \
   python3 python3-pip python3-venv python-is-python3 python3-pylsp nodejs npm \
   golang-go gopls openjdk-21-jdk-headless build-essential cmake ninja-build \
   clangd libicu76 libssl3t64 zlib1g libgssapi-krb5-2
uid=1000
if [ "$variant" = avf ]; then
  set -- "$@" openssh-server e2fsprogs
  # The Terminal guest's cloud-init creates its droid console user with UID 1000.
  uid=1100
fi
apt-get -y --no-install-recommends -o Dpkg::Options::=--force-confold install "$@"
groupadd -g "$uid" studio
useradd -u "$uid" -g studio -s /bin/bash -m studio
install -d -o studio -g studio /home/studio/projects
install -d /opt/dotnet /opt/pocket-studio/typescript-language-server /opt/pocket-studio/typescript /opt/pocket-studio/jdtls /opt/pocket-studio/csharp-ls
tar --no-same-owner -xzf "/cache/$SDK_ARCHIVE" -C /opt/dotnet
ln -s /opt/dotnet/dotnet /usr/local/bin/dotnet
tar --no-same-owner -xzf /cache/typescript-language-server.tgz --strip-components=1 -C /opt/pocket-studio/typescript-language-server
tar --no-same-owner -xzf /cache/typescript.tgz --strip-components=1 -C /opt/pocket-studio/typescript
ln -s /opt/pocket-studio/typescript/bin/tsc /usr/local/bin/tsc
ln -s /opt/pocket-studio/typescript/bin/tsserver /usr/local/bin/tsserver
mkdir -p /opt/pocket-studio/typescript-language-server/node_modules
ln -s /opt/pocket-studio/typescript /opt/pocket-studio/typescript-language-server/node_modules/typescript
chmod 0755 /opt/pocket-studio/typescript-language-server/lib/cli.mjs
ln -s /opt/pocket-studio/typescript-language-server/lib/cli.mjs /usr/local/bin/typescript-language-server
tar --no-same-owner -xzf /cache/jdtls-1.48.0.tar.gz -C /opt/pocket-studio/jdtls
chmod 0755 /opt/pocket-studio/jdtls/bin/jdtls
ln -s /opt/pocket-studio/jdtls/bin/jdtls /usr/local/bin/jdtls
unzip -q /cache/csharp-ls-0.27.0.nupkg -d /opt/pocket-studio/csharp-ls
printf '#!/bin/sh\nexec /opt/dotnet/dotnet /opt/pocket-studio/csharp-ls/tools/net10.0/any/CSharpLanguageServer.dll "$@"\n' > /usr/local/bin/csharp-ls
chmod 755 /usr/local/bin/csharp-ls
if [ "$variant" = avf ]; then
  mkdir -p /etc/pocket-studio
  install -m 644 /recipe/sshd_config /etc/pocket-studio/sshd_config
  install -m 644 /recipe/studio-ssh.service /etc/systemd/system/studio-ssh.service
  systemctl enable studio-ssh.service
  # Use the plugin's single key-only listener. Preserve the system Terminal's ttyd.
  systemctl disable ssh.service ssh.socket 2>/dev/null || true
  systemctl mask ssh.service ssh.socket
  usermod -p '*' studio
  rm -f /etc/ssh/ssh_host_* /etc/pocket-studio/ssh_host_*
else
  # Name service uses the runtime's bind-mounted resolver configuration.
  command -v sshd && exit 1
fi
rm -f /usr/sbin/policy-rc.d /etc/apt/apt.conf.d/98-image-build /etc/apt/sources.list
cp -a /tmp/studio-sources/sources.list.d/. /etc/apt/sources.list.d/
if [ -e /tmp/studio-sources/sources.list ]; then cp /tmp/studio-sources/sources.list /etc/apt/sources.list; fi
if [ -e /tmp/studio-sources/policy-rc.d ]; then cp -a /tmp/studio-sources/policy-rc.d /usr/sbin/policy-rc.d; fi
rm -rf /tmp/studio-sources /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /var/log/apt/* /var/log/dpkg.log
install -d /usr/share/doc/pocket-studio-image
dpkg-query -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n' > /usr/share/doc/pocket-studio-image/debian-packages.tsv
cp /recipe/SOURCES /usr/share/doc/pocket-studio-image/SOURCES
for tool in bash python node npm go dotnet java javac gcc g++ cmake ninja git pylsp gopls clangd typescript-language-server jdtls csharp-ls; do command -v "$tool"; done
export DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_CLI_HOME=/tmp/studio-dotnet-check
dotnet --list-sdks | grep '^10\.'
gcc --version >/dev/null
go version
javac -version
typescript-language-server --version
csharp-ls --version
rm -rf /tmp/studio-dotnet-check
