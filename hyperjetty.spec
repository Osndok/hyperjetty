
Name:           hyperjetty
Version:        alpha
Release:        1%{?dist}
Summary:        Jetty Servlet Hypervisor

Group:          Allogy/Infrastructure
License:        Proprietary
URL:            http://redmine.allogy.com/projects/hyperjetty

Source0:        hyperjetty-%{version}.tgz
Source1:        jetty-runner.jar

BuildArch:  noarch

BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

BuildRequires:  java
Requires:       java

%description

A very small & fault-tolerant jetty "hypervisor" that allocates,
tracks, and launches single-servlet jetty containers.

%prep
%setup -q


%build
#configure
make out/hyperjetty.jar


%install
rm   -rf $RPM_BUILD_ROOT
mkdir    $RPM_BUILD_ROOT

mkdir -p              $RPM_BUILD_ROOT/usr/lib/hyperjetty
cp out/hyperjetty.jar $RPM_BUILD_ROOT/usr/lib/hyperjetty/
cp %{SOURCE1}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar

cd       $RPM_BUILD_ROOT

mkdir -p ./var/lib/hyperjetty ./etc/init.d ./usr/bin/ ./var/log/hyperjetty

touch     ./usr/bin/hj
chmod 755 ./usr/bin/hj
cat  -  > ./usr/bin/hj <<EOF
#!/bin/bash
export CONTROL_PORT=5001
exec java -cp /usr/lib/hyperjetty/hyperjetty.jar com.allogy.hyperjetty.Client "$@"
EOF

touch     ./usr/bin/hyperjettyd
chmod 755 ./usr/bin/hyperjettyd
cat  -  > ./usr/bin/hyperjettyd <<EOF
#!/bin/bash

export CONTROL_PORT=5001
export LIB_DIRECTORY=/usr/lib/hyperjetty
export ETC_DIRECTORY=$LIB_DIRECTORY
export LOG_DIRECTORY=/var/log/hyperjetty
export JETTY_RUNNER_JAR=/var/lib/hyperjetty/jetty-runner.jar

OPTIONS="-Dvisualvm.display.name=Jetty-Hypervisor"
OPTIONS="$OPTIONS -Xmx33m"
OPTIONS="$OPTIONS -XX:MaxPermSize=15m"

exec java $OPTIONS -cp /usr/lib/hyperjetty/hyperjetty.jar com.allogy.hyperjetty.Service "$@"
EOF

touch     ./etc/init.d/hyperjetty
chmod 755 ./etc/init.d/hyperjetty
cat  -  > ./etc/init.d/hyperjetty <<EOF
#!/bin/bash

echo Unimplemented

EOF

%clean
rm -rf $RPM_BUILD_ROOT


%files
%defattr(-,hyperjetty,hyperjetty,-)
%doc
/usr/bin/hj
/usr/bin/hyperjettyd
/var/log/hyperjetty
/var/lib/hyperjetty
/usr/lib/hyperjetty
/etc/init.d/hyperjetty
