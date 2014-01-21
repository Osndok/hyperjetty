
Name:           hyperjetty
Version:        gamma
Release:        19
Summary:        Jetty Servlet Hypervisor

Group:          Allogy/Infrastructure
Vendor:         Allogy Interactive
License:        Proprietary
URL:            http://redmine.allogy.com/projects/hyperjetty

Source0:        hyperjetty-%{version}.tgz
Source2:        hyperjetty.init

# TODO: remove reduplication (jar is found *INSIDE* the tarball)
Source4:        junixsocket-1.3.jar
# TODO: split this off as another package... built from source or fetched from a trusted repo.
Source5:        junixsocket-1.3-bin.tar.bz2

# Must use version 8.x b/c 9.x is java 1.7 & has misc. broken plugins & major package-name breakage
Source1:        jetty-runner-8.1.13.v20130916.jar
Source3:        jetty-jmx-8.1.13.v20130916.jar

# Otherwise version 9.x (or later) supports multiple-configs-via-CLI-args w/o a patch to the "Runner" class
#ource1:        jetty-runner-9.0.3.v20130506.jar
#ource3:        jetty-jmx-9.0.3.v20130506.jar

#BuildArch:      noarch

BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

BuildRequires:  zip
Requires:       java

BuildRequires:  java-1.6.0-openjdk-devel
%define JAVA_BIN /usr/lib/jvm/java-1.6.0-openjdk/bin

Requires: libjunixsockets >= 1.5

%define __jar_repack %{nil}

%description

A very small & fault-tolerant jetty "hypervisor" that allocates,
tracks, and launches single-servlet jetty containers.

%package -n libjunixsockets

Version:   1.5
Release:   3
Group:     Allogy/Infrastructure
Summary:   JNI component that provides UNIX-domain-sockets to Java processes

%description -n libjunixsockets

JNI component that provides UNIX-domain-sockets to Java processes

%prep
%setup -q


%build
#configure

# Make sure we use the right java version
export PATH=%JAVA_BIN:$PATH

make out/hyperjetty.jar

# Only until 8.x has multi-configs or 9.x becomes usable
cat etc/Runner-8.1.11.v20130520-mod-multi-configs.java > etc/Runner.java
javac -cp %{SOURCE1}:%{SOURCE4} etc/Runner.java

cat etc/Request-8.1.13-mod-isSecure.java > etc/Request.java
javac -cp %{SOURCE1} etc/Request.java

%install
rm   -rf $RPM_BUILD_ROOT
mkdir    $RPM_BUILD_ROOT

mkdir -p              $RPM_BUILD_ROOT/sock/hj

tar xjf %{SOURCE5}
mkdir -p              $RPM_BUILD_ROOT/opt/newsclub
mv     */lib-native   $RPM_BUILD_ROOT/opt/newsclub
rm -rf junixsocket-*

mkdir -p              $RPM_BUILD_ROOT/usr/lib/hyperjetty
cp out/hyperjetty.jar $RPM_BUILD_ROOT/usr/lib/hyperjetty/
cp etc/jetty-jmx.xml  $RPM_BUILD_ROOT/usr/lib/hyperjetty/
cp %{SOURCE3}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-jmx.jar
cp %{SOURCE1}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar
cp %{SOURCE4}         $RPM_BUILD_ROOT/usr/lib/hyperjetty/junixsockets.jar

echo -e "\n\nHELP... performing jar-surgery on jetty-runner.jar for features.\n (1) multiple config files,\n (2) unix domain sockets\n\n"

mkdir -p org/mortbay/jetty/runner org/eclipse/jetty/server
cp etc/Runner.class   org/mortbay/jetty/runner/
cp etc/Request.class  org/eclipse/jetty/server/
cp etc/UNIXSocketConnector.class org/mortbay/jetty/runner/

zip -d $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar org/mortbay/jetty/runner/Runner.class
jar uf $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar org/mortbay/jetty/runner/Runner.class org/mortbay/jetty/runner/UNIXSocketConnector.class

zip -d $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar org/eclipse/jetty/server/Request.class
jar uf $RPM_BUILD_ROOT/usr/lib/hyperjetty/jetty-runner.jar org/eclipse/jetty/server/Request.class

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

unset LS_COLORS

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

#dont: getent group  hyperjetty >/dev/null || groupadd -r hyperjetty
#..or else this will fail with: "group already exists" :(
getent passwd hyperjetty >/dev/null || \
	/usr/sbin/useradd -c "Hyper Jetty" -s /sbin/nologin -r -d /var/lib/hyperjetty hyperjetty

%post
/sbin/chkconfig --add hyperjetty

%files
%defattr(-,hyperjetty,hyperjetty,-)
%doc
/usr/bin/hj
/usr/sbin/hyperjettyd
%dir /var/log/hyperjetty
%dir /sock/hj
/var/lib/hyperjetty
/usr/lib/hyperjetty
/etc/init.d/hyperjetty
#config(noreplace) /etc/sysconfig/hyperjetty


%files -n libjunixsockets
/opt/newsclub/lib-native
