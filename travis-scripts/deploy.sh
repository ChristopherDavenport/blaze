#!/bin/sh

if [ $TRAVIS_PULL_REQUEST != 'false' ]; then
    echo "Pull Requests are not released"
    export TRAVIS_ALLOW_FAILURE=true
    false

elif [ $TRAVIS_REPO_SLUG != "http4s/blaze" ]; then
    echo "Only Http4s/Blaze Repository Code is Released"
    export TRAVIS_ALLOW_FAILURE=true
    false

elif [ $TRAVIS_BRANCH != "master" ]; then
    echo "Only Master Branch is Released"
    export TRAVIS_ALLOW_FAILURE=true
    false

else
   sudo chmod +x /usr/local/bin/sbt # Temporary Fix For https://github.com/travis-ci/travis-ci/issues/7703
   sbt ++$TRAVIS_SCALA_VERSION 'release with-defaults'

fi

