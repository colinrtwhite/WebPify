# Building Butteraugli

Run the following commands in a Terminal window:

    cd ~
    git clone https://github.com/google/butteraugli
    cd butteraugli/src/
    make
    cd ~
    vim .bash_profile

Paste the following command in the text file on its own line:

    export PATH=$PATH:~/butteraugli/src/

And press the escape key then ":wq" then enter. Reboot your computer to update the path.
