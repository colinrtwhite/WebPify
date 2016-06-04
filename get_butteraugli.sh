#!/usr/bin/env bash
cd ~
git clone https://github.com/google/butteraugli
cd butteraugli/src/
make
cd ~
touch .bash_profile
echo 'export PATH=$PATH:~/butteraugli/src' >> .bash_profile
echo 'Reboot your PC to finish adding Butteraugli to your path.'