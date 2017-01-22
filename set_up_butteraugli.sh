#!/usr/bin/env bash
cd
git clone https://github.com/google/butteraugli
cd butteraugli/butteraugli/
make
cd
mkdir -p bin
cp butteraugli/butteraugli/butteraugli bin
rm -rf butteraugli
touch .bash_profile
echo 'export PATH=$PATH:~/bin' >> .bash_profile
