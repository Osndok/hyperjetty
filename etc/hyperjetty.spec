
Name:           hyperjetty
Version:        alpha
Release:        7
Summary:        Jetty Servlet Hypervisor

Group:          Allogy/Infrastructure
Vendor:         Allogy Interactive
License:        Proprietary
URL:            http://redmine.allogy.com/projects/hyperjetty

Source0:        hyperjetty-%{version}.tgz
Source1:        jetty-runner.jar
Source2:        hyperjetty.init
Source3:        jetty-jmx.jar

BuildArch:      noarch

BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

BuildRequires:  java
Requires:       java

%define __jar_repack %{nil}

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
cp etc/jetty-jmx.xml  $RPM_BUILD_ROOT/usr/lib/hyperjetty/
cp %{SOURCE1}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar
cp %{SOURCE3}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-jmx.jar

cd       $RPM_BUILD_ROOT

mkdir -p ./var/lib/hyperjetty ./etc/init.d ./usr/bin/ ./usr/sbin ./var/log/hyperjetty

touch     ./usr/bin/hj
chmod 755 ./usr/bin/hj
cat  -  > ./usr/bin/hj <<"EOF"
#!/bin/bash
export CONTROL_PORT=5001
exec java -cp /usr/lib/hyperjetty/hyperjetty.jar com.allogy.hyperjetty.Client "$@"
EOF

touch     ./usr/sbin/hyperjettyd
chmod 755 ./usr/sbin/hyperjettyd
cat  -  > ./usr/sbin/hyperjettyd <<"EOF"
#!/bin/bash

export CONTROL_PORT=5001
export LIB_DIRECTORY=/usr/lib/hyperjetty
export ETC_DIRECTORY=$LIB_DIRECTORY
export LOG_DIRECTORY=/var/log/hyperjetty
export JETTY_RUNNER_JAR=/usr/lib/hyperjetty/jetty-runner.jar

export JETTY_JMX_XML=/usr/lib/hyperjetty/jetty-jmx.xml
export JETTY_JMX_JAR=/usr/lib/hyperjetty/jetty-jmx.jar

OPTIONS="-Dvisualvm.display.name=Jetty-Hypervisor"
OPTIONS="$OPTIONS -Xmx33m"
OPTIONS="$OPTIONS -XX:MaxPermSize=15m"

exec java $OPTIONS -cp /usr/lib/hyperjetty/hyperjetty.jar com.allogy.hyperjetty.Service "$@"
EOF

cp %{SOURCE2} ./etc/init.d/hyperjetty
chmod 755 ./etc/init.d/hyperjetty

%clean
rm -rf $RPM_BUILD_ROOT

%pre
/usr/sbin/useradd -c "Hyper Jetty" -u 48 -s /sbin/nologin -r -d /var/lib/hyperjetty hyperjetty 2> /dev/null || :

%post
/sbin/chkconfig --add hyperjetty

%files
%defattr(-,hyperjetty,root,-)
%doc
/usr/bin/hj
/usr/sbin/hyperjettyd
%dir /var/log/hyperjetty
/var/lib/hyperjetty
/usr/lib/hyperjetty
/etc/init.d/hyperjetty
#config(noreplace) /etc/sysconfig/hyperjetty

