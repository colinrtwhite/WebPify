package com.colinrtwhite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.lang.Runtime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class Main {
	private static final String CP_COMMAND = "cp -r %s %s";
	private static final String COMPARE_PNGS_COMMAND = "compare_pngs %s %s";
	private static final String CWEBP_COMMAND = "cwebp -q %d %s -o %s";
	private static final String CWEBP_LOSSLESS_COMMAND = "cwebp -lossless -q %d %s -o %s";
	private static final String DWEBP_COMMAND = "dwebp %s -o %s";
	private static final String SIPS_COMMAND = "sips -s format png %s --out %s";
	private static final String RM_COMMAND = "rm %s";
	private static final String OLD_DIRECTORY_SUFFIX = "_old";
	private static final String JPG = ".jpg";
	private static final String JPEG = ".jpeg";
	private static final String PNG = ".png";
	private static final String WEBP = ".webp";
	private static final String NINE_PATCH = ".9.png";
	private static final ImageFilenameFilter imageFilter = new ImageFilenameFilter();
	private static final DirectoryFilenameFilter directoryFilter = new DirectoryFilenameFilter();
	private static final Runtime runtime = Runtime.getRuntime();
	private static boolean allowLossless = false, isRecursive = false;
	private static int numCPUs = 1;
	private static double qualityThreshold = 1;
	private static long totalOriginalSize = 0, bytesSaved = 0;
	private static File directory;
	private static ExecutorService executor;

	public static void main(final String[] args) {
		if (parseArguments(args)) {
			try {
				// Backup the input directory before compressing its files.
				runtime.exec(String.format(CP_COMMAND, directory.getCanonicalPath(),
						directory.getCanonicalPath() + OLD_DIRECTORY_SUFFIX)).waitFor();

				executor = Executors.newFixedThreadPool(numCPUs);
				if (!directory.exists()) {
					System.out.println("The specified directory does not exist.");
				} else if (!directory.isDirectory()) {
					System.out.println("The passed path is not a directory.");
				} else {
					traverseDirectory(directory);
				}
				executor.shutdown();
				executor.awaitTermination(7, TimeUnit.DAYS); // Essentially, run until done.
				System.out.println(String.format("Total compression savings: %d bytes (%.2f%% size reduction).",
						bytesSaved, totalOriginalSize == 0 ? 0 : 100.0 * bytesSaved / totalOriginalSize));
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			printUsage();
		}
	}

	private static boolean parseArguments(final String[] args) {
		try {
			if (args.length < 1) {
				return false;
			}

			// Read in the directory argument then any optional arguments.
			directory = new File(args[0]);
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
						} else if (expectsQualityNext) {
							qualityThreshold = Double.valueOf(args[i]);
						}
						expectsNumCPUsNext = false;
						expectsQualityNext = false;
						break;
				}
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void printUsage() {
		System.out.println("Usage: java -jar webpify.jar path_to_directory [[-n <number_of_processes>] [-q <quality_threshold>] [-r] [-l]]");
	}

	private static void traverseDirectory(final File directory) {
		executor.execute(() -> System.out.println("Compressing files in " + directory.getPath() + "..."));
		for (File file : directory.listFiles(imageFilter)) {
			executor.execute(() -> {
				// Compute some initial information about the image file.
				String path = file.getPath();
				String basePath = path.substring(0, path.lastIndexOf("."));
				String webPPath = basePath + WEBP;
				File webPFile = new File(webPPath);
				boolean needsConvertToPng = !file.getName().endsWith(PNG);
				boolean hasError = false;
				totalOriginalSize += file.length();

				try {
					if (searchForOptimalQuality(file, path, basePath, webPPath, webPFile, needsConvertToPng)) {
						// Attempt lossless compression if enabled (requires minSdk >= 18 on Android).
						if (allowLossless && getImageDissimilarity(100, path, basePath, webPPath,
								needsConvertToPng, true) < qualityThreshold && isSmallerFile(webPFile, file)) {
							bytesSaved += file.length() - webPFile.length();
							runtime.exec(String.format(RM_COMMAND, path));
						} else {
							System.out.println("The following image couldn't be compressed any further than it already is: " + file.getPath());
							runtime.exec(String.format(RM_COMMAND, webPPath));
						}
					} else {
						bytesSaved += (file.length() - webPFile.length());
						runtime.exec(String.format(RM_COMMAND, path));
					}
				} catch (Exception e) {
					System.out.println("An error occurred while compressing image: " + file.getPath());
					hasError = true;
				} finally {
					// Attempt to clean up the temporary PNG images.
					try {
						runtime.exec(String.format(RM_COMMAND, webPPath + PNG));
						if (needsConvertToPng) {
							runtime.exec(String.format(RM_COMMAND, basePath + PNG));
						}
						if (hasError) {
							runtime.exec(String.format(RM_COMMAND, webPPath));
						}
					} catch (Exception e) { /* Consume the exception. */ }
				}
			});
		}

		// Traverse any subdirectories.
		if (isRecursive) {
			for (File file : directory.listFiles(directoryFilter)) {
				traverseDirectory(file);
			}
		}
	}

	private static boolean searchForOptimalQuality(final File file, final String path, final String basePath,
			final String webPPath, final File webPFile, final boolean needsConvertToPng) throws IOException, InterruptedException {
		// Perform binary search to find the best WebP compression value.
		long min = 0, max = 100;
		while (min != max) {
			// Generate the temporary PNG images.
			long quality = Math.round((min + max) / 2.0);
			if (getImageDissimilarity(quality, path, basePath, webPPath, needsConvertToPng, false) <
					qualityThreshold && isSmallerFile(webPFile, file)) {
				// The image can be compressed more.
				if (max == quality) {
					break; // Prevent infinite loops.
				} else {
					max = quality;
				}
			} else {
				// Compression is now visible to the human eye (or the file got larger).
				// Scale the compression back a bit (if we can).
				if (min == quality) {
					break; // Prevent infinite loops.
				} else {
					min = quality;
				}
			}
		}
		// Return true if the binary search failed to find a valid compression value.
		return min == 100;
	}

	private static double getImageDissimilarity(final long quality, final String path,
			final String basePath, final String webPPath, final boolean needsConvertToPng, final boolean isLossless)
			throws IOException, InterruptedException {
		Main.runtime.exec(String.format(isLossless ? CWEBP_LOSSLESS_COMMAND : CWEBP_COMMAND, quality, path, webPPath)).waitFor();
		Main.runtime.exec(String.format(DWEBP_COMMAND, webPPath, webPPath + PNG)).waitFor();
		if (needsConvertToPng) {
			Main.runtime.exec(String.format(SIPS_COMMAND, path, basePath + PNG)).waitFor();
		}

		// Read in the dissimilarity result from Butteraugli.
		BufferedReader input = new BufferedReader(new InputStreamReader(
				Main.runtime.exec(String.format(COMPARE_PNGS_COMMAND, webPPath + PNG, basePath + PNG)).getInputStream()));
		double dissimilarity = Double.valueOf(input.readLine());
		input.close();
		return dissimilarity;
	}

	private static boolean isSmallerFile(final File fileA, final File fileB) {
		return fileA.length() < fileB.length();
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
