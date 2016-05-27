# WebPify
WebPify is a command line program for Mac OS X to batch compress .png, .jpeg, and .jpg images by converting them to .webp files.
The converter relies on [Google's Butteraugli](https://github.com/google/butteraugli) to measure the difference between images with respect to human vision.

The human eye doesn't notice very small changes to an image - a fact we can use for better image compression.
This script uses binary search to find the best lossy compression value for an image to maximize space savings with no perceivable visual degradation.

This script was inspired by [this blog post](https://medium.com/@duhroach/reducing-jpg-file-size-e5b27df3257c#.u6yh62vjk) by Colt McAnlis.

## Setting up
Install the WebP format through [Homebrew](http://brew.sh). Run the following command in a Terminal window:

    brew install webp

Build and add [Butteraugli](https://github.com/google/butteraugli) to your path. Make sure to leave the binary named "compare_pngs". If you're unsure how to install Butteraugli, see [here](butteraugli_instructions.md).

## Usage
Download the .jar file from the releases section.

    java -jar webpify.jar path_to_directory

Optional arguments:

**-r** : Iterate through all files in the directory recursively.

**-l** : Allow lossless WebP compression. On Android this is only supported on API >= 17.

**-n [number_of_processes]** : The number of seperate processes to create. The default is 1.

**-q [quality_threshold]** : The Butteraugli image difference threshold. Higher values allow for more compression, but can result in noticeable visual quality loss. Butteraugli defines 1.0 as "good". The default is 1.0.

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
