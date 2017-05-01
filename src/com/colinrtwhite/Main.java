package com.colinrtwhite;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class Main {
	private static final String JPG = ".jpg", JPEG = ".jpeg", PNG = ".png", WEBP = ".webp", NINE_PATCH = ".9.png",
			TEMP = ".temp", LOSSLESS = ".lossless";
	private static final int BUTTERAUGLI_MINIMUM_SIZE = 32;
	private static final DirectoryFilenameFilter DIRECTORY_FILTER = new DirectoryFilenameFilter();
	private static final ImageFilenameFilter IMAGE_FILTER = new ImageFilenameFilter();
	private static boolean allowLossless = false, isRecursive = false;
	private static int numCPUs = 2;
	private static double qualityThreshold = 1;
	private static long totalOriginalSize = 0, bytesSaved = 0;

	public static void main(final String[] args) {
		File directory = parseArguments(args);
		if (directory == null) {
			printUsage();
			return;
		}

		try {
			// Backup the input directory before compressing its files.
			new ProcessBuilder("cp", "-r", directory.getPath(), directory.getPath() + "_old").start().waitFor();

			ExecutorService executor = Executors.newFixedThreadPool(numCPUs);
			if (!directory.exists()) {
				System.out.println("The input directory does not exist.");
			} else if (!directory.isDirectory()) {
				System.out.println("The input path is not a directory.");
			} else {
				traverseDirectory(executor, directory);
			}
			executor.shutdown();
			executor.awaitTermination(7, TimeUnit.DAYS); // Essentially, run until done or killed.
			System.out.println(String.format("Total compression savings: %d bytes (%.2f%% size reduction).",
					bytesSaved, totalOriginalSize > 0 ? (100.0 * bytesSaved) / totalOriginalSize : 0));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static File parseArguments(final String[] args) {
		try {
			if (args.length < 1) {
				return null;
			}

			// Read in and validate any optional arguments.
			boolean expectsNumCPUsNext = false, expectsQualityNext = false;
			for (int i = 1; i < args.length; i++) {
				switch (args[i]) {
					case "-n":
						expectsNumCPUsNext = true;
						break;
					case "-q":
						expectsQualityNext = true;
						break;
					case "-r":
						isRecursive = true;
						break;
					case "-l":
						allowLossless = true;
						break;
					default:
						if (expectsNumCPUsNext) {
							numCPUs = Integer.valueOf(args[i]);
							expectsNumCPUsNext = false;
						} else if (expectsQualityNext) {
							if ((qualityThreshold = Double.valueOf(args[i])) <= 0) {
								throw new IllegalArgumentException("The quality threshold must be > 0.");
							}
							expectsQualityNext = false;
						}
						break;
				}
			}
			// Return the directory to show that we parse the arguments successfully.
			return new File(args[0]);
		} catch (Exception e) {
			return null;
		}
	}

	private static void printUsage() {
		System.out.println("Usage: java -jar webpify.jar /path/to/target/directory [[-n <number_of_processes>] [-q <quality_threshold>] [-r] [-l]]");
	}

	private static void traverseDirectory(final ExecutorService executor, final File directory) {
		Arrays.stream(getImagesOrDirectories(directory, true)).forEach(file -> executor.execute(() -> {
			// Compute some initial information about the image file.
			String path = file.getPath();
			String webPPath = path.substring(0, path.lastIndexOf(".")) + WEBP;
			File webPFile = new File(webPPath);
			long fileSize = file.length();
			totalOriginalSize += fileSize;

			try {
				// Compress the image with the lowest lossy quality value possible that results in an image below the dissimilarity threshold.
				// Check that it is smaller than the original file.
				boolean hasValidCompressionValue = searchForOptimalLossyQuality(path, webPPath) && webPFile.length() < fileSize;

				// If enabled, attempt lossless compression.
				if (allowLossless) {
					String webPLosslessPath = webPPath + LOSSLESS;
					try {
						// If the lossless result is smaller than the lossy result, overwrite the lossy file with it.
						if (getImageDissimilarity(100, path, webPLosslessPath, true) < qualityThreshold &&
								new File(webPLosslessPath).length() < Math.min(webPFile.length(), fileSize)) {
							new ProcessBuilder("mv", webPLosslessPath, webPPath).start().waitFor();
							hasValidCompressionValue = true;
						}
					} catch (Exception e) {
						// Consume the exception.
					} finally {
						// Clean up any temporary images.
						try {
							new ProcessBuilder("rm", webPLosslessPath).start();
						} catch (Exception e) { /* Consume the exception. */ }
					}
				}

				if (hasValidCompressionValue) {
					bytesSaved += (fileSize - webPFile.length());
					new ProcessBuilder("rm", path).start();
					System.out.println("Successfully compressed file: " + path);
				} else {
					new ProcessBuilder("rm", webPPath).start();
					System.out.println("The following image couldn't be compressed any further: " + path);
				}
			} catch (Exception e) {
				System.out.println("An error occurred while compressing image: " + path);
				try {
					new ProcessBuilder("rm", webPPath).start();
				} catch (Exception e2) { /* Consume the exception. */ }
			}
		}));

		// Traverse any subdirectories.
		if (isRecursive) {
			Arrays.stream(getImagesOrDirectories(directory, false)).forEach(file -> traverseDirectory(executor, file));
		}
	}

	private static File[] getImagesOrDirectories(final File directory, final boolean getImages) {
		File[] files = getImages ? directory.listFiles(IMAGE_FILTER) : directory.listFiles(DIRECTORY_FILTER);
		return files != null ? files : new File[0];
	}

	private static boolean searchForOptimalLossyQuality(final String path, final String webPPath) {
		// Perform binary search to find the best WebP compression value.
		int min = 0, max = 100;
		while (min != max) {
			int quality = (int) Math.round((min + max) / 2.0);
			if (getImageDissimilarity(quality, path, webPPath, false) < qualityThreshold) {
				// The image can be compressed more.
				max = (max == quality) ? quality - 1 : quality;
			} else {
				// Scale the compression back a bit.
				min = (min == quality) ? quality + 1 : quality;
			}
		}

		// Return true if the binary search found a valid compression value.
		return min != 100;
	}

	private static double getImageDissimilarity(final int quality, final String path, final String webPPath, final boolean isLossless) {
		double dissimilarity = Double.MAX_VALUE;
		String webPPNGPath = webPPath + PNG, pathTemp = path + TEMP;
		try {
			// Generate a WebP version of the image at the input quality.
			List<String> arguments = Arrays.asList("cwebp", "-af", "-mt", "-q", String.valueOf(quality), path, "-o", webPPath);
			if (isLossless) {
				(arguments = new ArrayList<>(arguments)).add(1, "-lossless");
			}
			new ProcessBuilder(arguments).start().waitFor();

			// Convert the WebP image back into a PNG.
			new ProcessBuilder("dwebp", webPPath, "-o", webPPNGPath).start().waitFor();

			// Create a copy of the original file.
			new ProcessBuilder("cp", "-r", path, pathTemp).start().waitFor();

			// Perform some optional image transformations.
			boolean isPNG = path.endsWith(PNG);
			try (BufferedReader input = new BufferedReader(new InputStreamReader(
					new ProcessBuilder("file", pathTemp).start().getInputStream()))) {
				// Ensure both images have the alpha channel if the source is a PNG.
				if (isPNG) {
					new ProcessBuilder("convert", webPPNGPath, "-alpha", "on", webPPNGPath).start().waitFor();
					new ProcessBuilder("convert", pathTemp, "-alpha", "on", pathTemp).start().waitFor();
				}

				// Resize the image if it is too small to work with Butteraugli.
				String[] segments = input.readLine().split(",");
				String[] dimensions = segments[isPNG ? 1 : segments.length - 2].split("x");
				double width = Integer.valueOf(dimensions[0].trim());
				double height = Integer.valueOf(dimensions[1].trim());
				if (Math.min(width, height) < BUTTERAUGLI_MINIMUM_SIZE) {
					int newHeight = (int) Math.ceil((BUTTERAUGLI_MINIMUM_SIZE / Math.min(width, height)) * Math.max(width, height));
					new ProcessBuilder("sips", "-Z", String.valueOf(newHeight), pathTemp).start().waitFor();
					new ProcessBuilder("sips", "-Z", String.valueOf(newHeight), webPPNGPath).start().waitFor();
				}
			} catch (Exception e) { /* Consume the exception. */ }

			// Read in the dissimilarity result from Butteraugli.
			try (BufferedReader input = new BufferedReader(new InputStreamReader(
					new ProcessBuilder("butteraugli", webPPNGPath, pathTemp).start().getInputStream()))) {
				dissimilarity = Double.valueOf(input.readLine());
			}
		} catch (Exception e) {
			// Consume the exception.
		} finally {
			// Clean up any temporary images.
			try {
				new ProcessBuilder("rm", webPPNGPath).start();
			} catch (Exception e) { /* Consume the exception. */ }
			try {
				new ProcessBuilder("rm", pathTemp).start();
			} catch (Exception e) { /* Consume the exception. */ }
		}
		return dissimilarity;
	}

	private static class ImageFilenameFilter implements FilenameFilter {
		@Override
		public boolean accept(final File current, final String name) {
			// 9-patch images are not supported in the WebP format.
			return name.endsWith(JPG) || name.endsWith(JPEG) || (name.endsWith(PNG) && !name.endsWith(NINE_PATCH));
		}
	}

	private static class DirectoryFilenameFilter implements FileFilter {
		@Override
		public boolean accept(final File file) {
			return file.isDirectory();
		}
	}
}
