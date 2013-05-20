
NAME = hyperjetty

VERSION = $(shell cat .version)

JAVA_SRC = $(shell find src -name '*.java')

out/hyperjetty.jar: $(JAVA_SRC)
	rm       -rf out/classes
	mkdir     -p out/classes
	javac     -d out/classes $^
	jar cf $@ -C out/classes com

ALWAYS:
archive: out/$(NAME)-$(VERSION).tgz

out/$(NAME)-$(VERSION).tgz: ALWAYS
	mkdir -p out
	git archive --format=tar --prefix=$(NAME)-$(VERSION)/ HEAD | gzip > out/$(NAME)-$(VERSION).tgz

rpm: out/hyperjetty.jar hyperjetty.spec
	rpmbuild -ba

send: archive
	scp out/$(NAME)-$(VERSION).tgz devel:/root/rpmbuild/SOURCES/
	scp etc/hyperjetty.init        devel:/root/rpmbuild/SOURCES/
	scp etc/hyperjetty.spec devel:/tmp/
