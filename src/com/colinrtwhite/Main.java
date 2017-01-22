package com.colinrtwhite;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class Main {
	private static final String JPG = ".jpg", JPEG = ".jpeg", PNG = ".png", WEBP = ".webp", NINE_PATCH = ".9.png", ORIGINAL = ".original";
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
			boolean hasError = false;
			long fileSize = file.length();
			totalOriginalSize += fileSize;

			try {
				// Compress the image with the lowest quality value possible that results in an image below the dissimilarity threshold.
				// Check that it is smaller than the original file.
				if (searchForOptimalQuality(path, webPPath) && webPFile.length() < file.length()) {
					bytesSaved += (fileSize - webPFile.length());
					new ProcessBuilder("rm", path).start();
					System.out.println("Successfully compressed file: " + path);
				} else {
					new ProcessBuilder("rm", webPPath).start();
					System.out.println("The following image couldn't be compressed any further: " + path);
				}
			} catch (Exception e) {
				System.out.println("An error occurred while compressing image: " + path);
				hasError = true;
			} finally {
				try {
					// Clean up any temporary PNG images.
					new ProcessBuilder("rm", webPPath + PNG).start();
					if (hasError) {
						new ProcessBuilder("rm", webPPath).start();
					}
				} catch (Exception e) { /* Consume the exception. */ }
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

	private static boolean searchForOptimalQuality(final String path, final String webPPath) throws IOException, InterruptedException {
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
		// If we couldn't find a valid compression value, attempt lossless compression (if enabled).
		return min != 100 || (allowLossless && getImageDissimilarity(100, path, webPPath, true) < qualityThreshold);
	}

	private static double getImageDissimilarity(final int quality, final String path, final String webPPath,
			final boolean isLossless) throws IOException, InterruptedException {
		// Generate a WebP version of the image at the input quality.
		List<String> arguments = Arrays.asList("cwebp", "-q", String.valueOf(quality), path, "-o", webPPath);
		if (isLossless) {
			(arguments = new ArrayList<>(arguments)).add(1, "-lossless");
		}
		new ProcessBuilder(arguments).start().waitFor();
		new ProcessBuilder("dwebp", webPPath, "-o", webPPath + PNG).start().waitFor();

		// Verify that the image dimensions aren't too small to work with Butteraugli.
		BufferedReader input = null;
		boolean performResize = false;
		try {
			input = new BufferedReader(new InputStreamReader(new ProcessBuilder("file", path).start().getInputStream()));
			String[] segments = input.readLine().split(",");
			String[] dimensions = segments[path.endsWith(PNG) ? 1 : segments.length - 2].split("x");
			double width = Integer.valueOf(dimensions[0].trim());
			double height = Integer.valueOf(dimensions[1].trim());
			if (performResize = Math.min(width, height) < BUTTERAUGLI_MINIMUM_SIZE) {
				// The image is too small to work with Butteraugli. Resize it.
				new ProcessBuilder("cp", "-r", path, path + ORIGINAL).start().waitFor();
				new ProcessBuilder("cp", "-r", webPPath, webPPath + ORIGINAL).start().waitFor();
				int newHeight = (int) Math.ceil((BUTTERAUGLI_MINIMUM_SIZE / Math.min(width, height)) * Math.max(width, height));
				new ProcessBuilder("sips", "-Z", String.valueOf(newHeight), path).start().waitFor();
				new ProcessBuilder("sips", "-Z", String.valueOf(newHeight), webPPath + PNG).start().waitFor();
			}
		} catch (Exception e) {
			// Something went wrong while verifying the size. Proceed anyways.
		} finally {
			if (input != null) {
				input.close();
			}
		}

		// Read in the dissimilarity result from Butteraugli.
		input = new BufferedReader(new InputStreamReader(new ProcessBuilder("butteraugli", webPPath + PNG, path).start().getInputStream()));
		String line = input.readLine();
		input.close();
		if (performResize) {
			new ProcessBuilder("mv", path + ORIGINAL, path).start().waitFor();
			new ProcessBuilder("mv", webPPath + ORIGINAL, webPPath).start().waitFor();
		}
		return Double.valueOf(line);
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
