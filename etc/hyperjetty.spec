
Name:           hyperjetty
Version:        alpha
Release:        16
Summary:        Jetty Servlet Hypervisor

Group:          Allogy/Infrastructure
Vendor:         Allogy Interactive
License:        Proprietary
URL:            http://redmine.allogy.com/projects/hyperjetty

Source0:        hyperjetty-%{version}.tgz
Source2:        hyperjetty.init

# Must use version 8.x b/c 9.x is java 1.7 & has misc. broken plugins & major package-name breakage
Source1:        jetty-runner-8.1.11.v20130520.jar
Source3:        jetty-jmx-8.1.11.v20130520.jar

# Otherwise version 9.x (or later) supports multiple-configs-via-CLI-args w/o a patch to the "Runner" class
#ource1:        jetty-runner-9.0.3.v20130506.jar
#ource3:        jetty-jmx-9.0.3.v20130506.jar

BuildArch:      noarch

BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

BuildRequires:  zip
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

# Only until 8.x has multi-configs or 9.x becomes usable
cat etc/Runner-8.1.11.v20130520-mod-multi-configs.java > etc/Runner.java
javac -cp %{SOURCE1} etc/Runner.java

%install
rm   -rf $RPM_BUILD_ROOT
mkdir    $RPM_BUILD_ROOT

mkdir -p              $RPM_BUILD_ROOT/usr/lib/hyperjetty
cp out/hyperjetty.jar $RPM_BUILD_ROOT/usr/lib/hyperjetty/
cp etc/jetty-jmx.xml  $RPM_BUILD_ROOT/usr/lib/hyperjetty/
cp %{SOURCE3}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-jmx.jar
cp %{SOURCE1}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar

echo -e "\n\nHELP... have to perform jar-surgury on jetty-runner.jar for a trivial feature (!!!)\n\n"

mkdir -p org/mortbay/jetty/runner
cp etc/Runner.class org/mortbay/jetty/runner/
zip -d $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar org/mortbay/jetty/runner/Runner.class
jar uf $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar org/mortbay/jetty/runner/Runner.class

rm -rfv org

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
# Add the "apache" user
getent group  hyperjetty >/dev/null || groupadd -r hyperjetty
getent passwd hyperjetty >/dev/null || \
	/usr/sbin/useradd -c "Hyper Jetty" -s /sbin/nologin -r -d /var/lib/hyperjetty hyperjetty 2> /dev/null || :

%post
/sbin/chkconfig --add hyperjetty

%files
%defattr(-,hyperjetty,hyperjetty,-)
%doc
/usr/bin/hj
/usr/sbin/hyperjettyd
%dir /var/log/hyperjetty
/var/lib/hyperjetty
/usr/lib/hyperjetty
/etc/init.d/hyperjetty
#config(noreplace) /etc/sysconfig/hyperjetty

