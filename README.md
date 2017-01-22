# WebPify
WebPify is a command line program for MacOS to batch compress .png and .jpg images by converting them to [.webp](https://developers.google.com/speed/webp/) files. The converter relies on [Google's Butteraugli](https://github.com/google/butteraugli) to measure the visual difference between images.

The human eye can't perceive very small changes to an image - a fact we can use for better image compression.
This program uses binary search to find the best lossy compression value for an image to maximize space savings with no perceivable visual degradation. If no suitable lossy compression value is found, it will fall back on loseless compression.

This program was inspired by [this blog post](https://medium.com/@duhroach/reducing-jpg-file-size-e5b27df3257c#.u6yh62vjk) by Colt McAnlis.

## Set Up
Install the libjpeg, libpng, and the WebP format through [Homebrew](http://brew.sh). Run the following commands in a Terminal window:

    brew install libjpeg libpng webp

Download [this shell script](set_up_butteraugli.sh) and run it like so:

    chmod +x set_up_butteraugli.sh
    ./set_up_butteraugli.sh
    . ~/.bash_profile

And that's it!

## Usage
Download the .jar file from the releases section.

    java -jar webpify.jar /path/to/target/directory

Optional arguments:

**-l** : Enable lossless WebP compression. This is only supported in 4.2.1 (technically API 18) and above on Android.

**-r** : Enable recursive search for image files in the input directory and its subdirectories.

**-q [quality_threshold]** : Higher quality threshold values allow for more compression, but can result in noticeable visual quality loss. The default value of 1.0 is tuned for no perceivable quality loss.

**-n [number_of_processes]** : The number of processes to run in parallel. The default value is 2.

## License
    Copyright 2016 Colin White

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
