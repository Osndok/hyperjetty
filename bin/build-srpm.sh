#!/bin/bash
#
# Builds an SRPM for this project, and stashes it on the specified devtools repo.
#
# Last touch: 2014-04-14 / REH
#
# (1) build slaves must be able to log into devtools & use 'mock'
#
#

PROJECT=hyperjetty

SPEC_IN=etc/$PROJECT.spec.in
SPEC_OUT=target/$PROJECT.spec

BUILD_MACHINE=builder@devtools.allogy.com

# ----------------------------------------------

set -eu

[ -n "${REPO_NAME:-}" ] || REPO_NAME=snapshot

VERSION=$(cat .version)
echo "VERSION=$VERSION"

# http://semver.org/
MAJOR=$(echo $VERSION | cut -f1 -d.)
MINOR=$(echo $VERSION | cut -f2 -d.)
PATCH=$(echo $VERSION | cut -f3 -d.)
BUILD=${BUILD_NUMBER:-0}

echo "MAJOR=$MAJOR"
echo "MINOR=$MINOR"
echo "PATCH=$PATCH"
echo "BUILD=$BUILD"

SELF_SOURCE_DIR="$PROJECT-$VERSION/"
SELF_SOURCE_REFERENCE="$PROJECT-$VERSION.tar.gz"
echo "SSR=$SELF_SOURCE_REFERENCE"

sed -e "s/@MAJOR@/$MAJOR/g" -e "s/@MINOR@/$MINOR/g" -e "s/@PATCH@/$PATCH/g" -e "s/@BUILD@/$BUILD/g" "$SPEC_IN" > "$SPEC_OUT"

SPEC_BASE=$(basename $SPEC_OUT)

LOCAL_DIR=$(mktemp -d /tmp/build-srpm.XXXXXXXXX)

SOURCES_LIST=$LOCAL_DIR/sources.txt

cat $SPEC_OUT | ssh "$BUILD_MACHINE" spectool - | tr -s ' :' ':' | cut -f2- -d: > $SOURCES_LIST

#
# SOURCES_LIST is a file that contains a list of all macro-expanded "Source" references
# from the rpm spec file.
#
# Examples:
#
#  "target/some.jar" 
#  "module/target/some.jar" - something that this project builds (i.e. maven output)
#
#  "machine:/usr/src/archive.tgz" - a file, not generally accessible, that we are keeping.
#
#  "http://some.host/project/archive.tgz" - a publicly reachable file
#
#  "https://jenkins.allogy.com/job/Project/artifact/blah.jar"
#            - does not work ATM due to permission issues
#
#
# NB: reachability is not super-imperative, because the sources are bundled into the final
#     "source rpm" (SRPM) which is, effectively, the unit of build information. Therefore,
#     if a publicly-reachable file drops offline, we might not notice at first (b/c the
#     acquire step will cache it); but when we do (e.g. due to a broken build), we can
#     re-acquire the file by extracting it from the last successful SRPM build *even* if
#     it has dropped from the acquire cache.
#

REMOTE_DIR=$(ssh "$BUILD_MACHINE" mktemp -d /tmp/build-srpm.XXXXXXXXX)
REMOTE="$BUILD_MACHINE:$REMOTE_DIR"

function xfer_jenkins_url()
{
	echo "JENKINS_URL=$SOURCE"
	ssh "$BUILD_MACHINE" /builder/acquire-jenkins.sh "$REMOTE_DIR" "$SOURCE"
}

function xfer_source_url()
{
	echo SOURCE_URL=$SOURCE
	ssh "$BUILD_MACHINE" /builder/acquire-url.sh "$REMOTE_DIR" "$SOURCE"
}

function xfer_machine_ref()
{
	echo MACHINE_REF=$SOURCE
	ssh "$BUILD_MACHINE" /builder/acquire-scp.sh "$REMOTE_DIR" "$SOURCE"
}

function xfer_build_output()
{
	echo BUILD_OUTPUT=$SOURCE
	if_source_is_safe
	scp "$SOURCE" "$REMOTE"
}

function xfer_in_repo_file()
{
	echo SOURCE_FILE=$SOURCE
	if_source_is_safe
	scp "$SOURCE" "$REMOTE"
}

function xfer_self_source()
{
	echo "SELF_SOURCE=$SOURCE"
	git archive --format=tar --prefix="$SELF_SOURCE_DIR" HEA | gzip > $LOCAL_DIR/$SELF_SOURCE_REFERENCE
	scp "$LOCAL_DIR/$SELF_SOURCE_REFERENCE" "$REMOTE"
}

function if_source_is_safe()
{
	if [[ "$SOURCE" == *../* ]]; then
		echo 1>&2 "UNSAFE PATH 1: $SOURCE"
		exit 2
	elif [[ "$SOURCE" == /* ]]; then
		echo 1>&2 "UNSAFE PATH 2: $SOURCE"
		exit 3
	fi
}

#--------------
# TEST VECTORS
#--------------
#
# SOURCE=target/hyperjetty.spec
# SOURCE=hyperjetty-webapp/target/hyperjetty-webapp.jar
# SOURCE=http://redmine.allogy.com/attachments/download/1053/onboarding-webflow.pdf
# SOURCE=https://redmine.allogy.com/attachments/download/1053/onboarding-webflow.pdf
# SOURCE=devtools:/usr/src/epel-release-6-5.noarch.rpm
#

while read SOURCE
do
	echo "SOURCE=$SOURCE"

	if [ "$SOURCE" == "$SELF_SOURCE_REFERENCE" ] || [[ "$SOURCE" == */$SELF_SOURCE_REFERENCE ]]; then
		xfer_self_source
	elif [[ $SOURCE =~ ^[^/]+://jenkins.allogy.com ]]; then
		xfer_jenkins_url
	elif [[ $SOURCE =~ ^[^/]+:// ]]; then
		xfer_source_url
	elif [[ $SOURCE =~ ^[-a-z_]+:/ ]]; then
		xfer_machine_ref
	elif [[ $SOURCE == target/* ]] || [[ $SOURCE == */target/* ]]; then
		xfer_build_output
	elif [ -f "$SOURCE" ]; then
		xfer_in_repo_file
	else
		echo 2>&1 "unrecognized dependency pattern: $SOURCE"
		exit 1
	fi

done < $SOURCES_LIST

scp "$SPEC_OUT" "$REMOTE"

if ssh "$BUILD_MACHINE" /builder/register-srpm.sh "$REPO_NAME" "$REMOTE_DIR" ; then
	rm -rf "$LOCAL_DIR"
	echo 1>&2 "$0: SUCCESS"
	exit 0
else
	rm -rf "$LOCAL_DIR"
	echo 1>&2 "$0: FAILURE"
	exit 1
fi
