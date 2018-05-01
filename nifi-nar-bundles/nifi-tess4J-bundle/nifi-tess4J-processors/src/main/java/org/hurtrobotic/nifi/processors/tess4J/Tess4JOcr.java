/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hurtrobotic.nifi.processors.tess4J;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.util.PdfGsUtilities;

@Tags({ "tesseractOcr", "tess4J" })
@EventDriven
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@CapabilityDescription("Perform Tesseract OCR on pdf or image flowfile. ")
@WritesAttributes({
		@WritesAttribute(attribute = "file.source.filename", description = "The filename of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.mime.type", description = "The mime type of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.uuid", description = "The UUID of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.format", description = "The extension file of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.page.count", description = "The page count of the source FlowFile"),
		@WritesAttribute(attribute = "output.language", description = "The language used by Tesseract OCR"),
		@WritesAttribute(attribute = "output.ocr.uuid", description = "The UUID of the produced OCR text file"),
		@WritesAttribute(attribute = "output.img.uuid", description = "The UUID of the document") })

public class Tess4JOcr extends AbstractTesseractOcr {
	private static final String SOURCE_FILENAME = "file.source.filename";
	private static final String SOURCE_MIME_TYPE = "file.source.mime.type";
	private static final String SOURCE_UUID = "file.source.uuid";
	private static final String SOURCE_FORMAT = "file.source.format";
	private static final String SOURCE_PAGE_COUNT = "file.source.page.count";
	private static final String OUTPUT_LANGUAGE = "output.language";
	private static final String OUTPUT_OCR_UUID = "output.ocr.uuid";
	private static final String OUTPUT_IMG_UUID = "output.img.uuid";
	private static final ContentInfoUtil contentInfoUtil = new ContentInfoUtil();

	private Properties mappingIso639Part3;
	private List<PropertyDescriptor> descriptors;
	private Set<Relationship> relationships;
	private static final Map<ProcessSession, ContentInfo> contentInfoProcessSession = new HashMap<ProcessSession, ContentInfo>();
	private static final Set<String>allowedLanguages = new HashSet<String>( Arrays.asList("afr", "ara", "aze", "bel", "ben", "bul", "cat", "ces", "chi-sim", "chi-tra", "chr", "dan",
			"deu", "deu-frak", "dev", "ell", "eng", "enm", "epo", "equ", "est", "eus", "fin", "fra", "frk",
			"frm", "glg", "grc", "heb", "hin", "hrv", "hun", "ind", "isl", "ita", "ita-old", "jpn", "kan",
			"kor", "lav", "lit", "mal", "mkd", "mlt", "msa", "nld", "nor", "osd", "pol", "por", "ron", "rus",
			"slk", "slk-frak", "slv", "spa", "spa-old", "sqi", "srp", "swa", "swe", "tam", "tel", "tgl", "tha",
			"tur", "ukr", "vie"));

	public static final Relationship REL_FILES = new Relationship.Builder().name("files")
			.description("Each individual file will be routed to the files relationship").build();

	public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
			.description("Flowfiles that could not be processed").build();

	public static final PropertyDescriptor FILENAME_LANGUAGE_EXTRACTION_MODE = new PropertyDescriptor.Builder()
			.name("filename.language.extraction.mode").displayName("language Iso extraction mode")
			.description("Mode used for extracting iso code from Flowfile filename. "
					+ "Valid values are regex textdetection regexORtextdetection textdetectionORregex.")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).expressionLanguageSupported(true).defaultValue("none")
			.allowableValues("regex", "textdetection", "regexORtextdetection", "textdetectionORregex", "none")
			.required(true).build();

	public static final PropertyDescriptor FILENAME_LANGUAGE_EXTRACTION_REGEX = new PropertyDescriptor.Builder()
			.name("filename.language.extraction.regex").displayName("Language Iso code extraction (Regex)")
			.description("A Regular Expression that is matched against FlowFile filename. "
					+ "If an attribute match the regex group(1), the language value extracted is used for OCR. "
					+ "Iso supported format 639-3 (eng) or 639-2 (en), no case sensitive")
			.addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR).expressionLanguageSupported(true)
			.defaultValue("\".*[-|_](\\\\w{2,3})\\\\.\\\\w{3}\"").required(false).build();

	public static final PropertyDescriptor TESSERACT_INSTALL_DIR = new PropertyDescriptor.Builder()
			.name("tesseract.install.dir").displayName("Tesseract Installation Directory")
			.description("Base location on the local filesystem where Tesseract is installed")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).expressionLanguageSupported(true)
			.defaultValue("/usr/share/tesseract-ocr/tessdata/").required(true).build();

	public static final PropertyDescriptor TESSERACT_ENGINE_MODE = new PropertyDescriptor.Builder()
			.name("tesseract.engine.mode").displayName("OCR engine mode")
			.description("0 - 2. @see https://code.google.com/archive/p/tesseract-ocr-extradocs/wikis/Cube.wiki "
					+ "(0)-Use original Tesseract recognition engine. " + "(1)-Use cube recognition engine. "
					+ "(2)-Use both engines, automatically choosing whichever appears to give better results ")
			.addValidator(StandardValidators.INTEGER_VALIDATOR).expressionLanguageSupported(true).defaultValue("2")
			.required(true).build();

	public static final PropertyDescriptor TESSERACT_PAGE_SEG_MODE = new PropertyDescriptor.Builder()
			.name("tesseract.page.seg.mode").displayName("Page segmentation mode")
			.description("0 - 13. @see https://github.com/tesseract-ocr/tesseract/wiki/ImproveQuality "
					+ "(0)-Orientation and script detection (OSD) only. " + "(1)-Automatic page segmentation with OSD. "
					+ "(2)-Automatic page segmentation, but no OSD, or OCR. "
					+ "(3)-Fully automatic page segmentation, but no OSD. (Default) "
					+ "(4)-Assume a single column of text of variable sizes. "
					+ "(5)-Assume a single uniform block of vertically aligned text. "
					+ "(6)-Assume a single uniform block of text. " + "(7)-Treat the image as a single text line. "
					+ "(8)-Treat the image as a single word. " + "(9)-Treat the image as a single word in a circle. "
					+ "(10)-Treat the image as a single character. "
					+ "(11)-Sparse text. Find as much text as possible in no particular order. "
					+ "(12)-Sparse text with OSD. "
					+ "(13)-Raw line. Treat the image as a single text line, bypassing hacks that are Tesseract-specific. ")
			.addValidator(StandardValidators.INTEGER_VALIDATOR).expressionLanguageSupported(true).defaultValue("3")
			.required(true).build();

	public static final PropertyDescriptor DEFAULT_LANGUAGE = new PropertyDescriptor.Builder().name("default.language")
			.displayName("Default language")
			.description("Default language if detection failed or extraction mode set to none.")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).expressionLanguageSupported(true).defaultValue("eng")
			.allowableValues(allowedLanguages)
			.required(true).build();

	protected void init(final ProcessorInitializationContext context) {
		getLogger().info("Start Init.");
		final Set<Relationship> relationships = new HashSet<Relationship>();
		final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
		/*
		 * ClassLoader classLoader = getClass().getClassLoader(); final String
		 * propFileName = "mapping_iso639.properties"; try { mappingIso639Part3 = new
		 * Properties();
		 * mappingIso639Part3.load(classLoader.getResourceAsStream(propFileName)); }
		 * catch (Exception e) { // e.printStackTrace();
		 * getLogger().error("Error during processing", new Object[] { context, e });
		 * 
		 * }
		 */
		relationships.add(REL_FILES);
		relationships.add(REL_FAILURE);
		this.relationships = Collections.unmodifiableSet(relationships);

		descriptors.add(FILENAME_LANGUAGE_EXTRACTION_MODE);
		descriptors.add(FILENAME_LANGUAGE_EXTRACTION_REGEX);
		descriptors.add(TESSERACT_INSTALL_DIR);
		descriptors.add(TESSERACT_ENGINE_MODE);
		descriptors.add(TESSERACT_PAGE_SEG_MODE);
		descriptors.add(DEFAULT_LANGUAGE);
		this.descriptors = Collections.unmodifiableList(descriptors);
		getLogger().info("End Init.");
	}

	public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
		getLogger().info("Start Processing.");
		FlowFile originalFlowFile = session.get();
		if (originalFlowFile == null) {
			return;
		}
		List<FlowFile> outputFlowFileList = new ArrayList<>();
		List<FlowFile> invalidFlowFilesList = new ArrayList<>();

		Map<String, String> attributes = new HashMap<>();
		Map<String, String> txtFileAttributes = new HashMap<>();

		getLogger().info("Processing Mime detection.");
		session.read(originalFlowFile, new InputStreamCallback() {
			public void process(InputStream rawIn) throws IOException {
				try (final InputStream in = new BufferedInputStream(rawIn)) {
					ContentInfo info = contentInfoUtil.findMatch(in);
					attributes.put(SOURCE_FORMAT, info.getName());
					attributes.put(CoreAttributes.MIME_TYPE.key(), info.getMimeType());
				} catch (Exception e) {
					getLogger().error("Error during processing", new Object[] { context, e });
				}
			}
		});
		getLogger().info("Ending Mime detection.");
		session.read(originalFlowFile, new InputStreamCallback() {

			public void process(InputStream rawIn) throws IOException {

				File tmpProcessing = null;
				File imgFile = null;
				File textFile = null;

				try (final InputStream in = new BufferedInputStream(rawIn)) {
					getLogger().info("Start Image Processing.");
					System.setProperty("jna.encoding", "UTF8");
					String originalFileName = originalFlowFile.getAttribute(CoreAttributes.FILENAME.key());
					String originalMimeType = originalFlowFile.getAttribute(CoreAttributes.MIME_TYPE.key());
					String originalUUID = originalFlowFile.getAttribute(CoreAttributes.UUID.key());
					ContentInfo pdfContent = ContentInfoUtil.findExtensionMatch("PDF");
					ContentInfo textContent = ContentInfoUtil.findExtensionMatch("TXT");

					tmpProcessing = File.createTempFile("processing", "bin");
					FileOutputStream fos = new FileOutputStream(tmpProcessing);
					IOUtils.copy(in, fos);
					fos.close();

					/*
					 * Retrieving Extraction Mode.
					 */
					String isoLanguage = null;
					final String fileNameExtractionMode = context.getProperty(FILENAME_LANGUAGE_EXTRACTION_MODE)
							.evaluateAttributeExpressions(originalFlowFile).getValue();

					getLogger().info("Processing language.");
					if (fileNameExtractionMode.equalsIgnoreCase("none")) {
						isoLanguage = context.getProperty(DEFAULT_LANGUAGE)
								.evaluateAttributeExpressions(originalFlowFile).getValue();
					} else if (fileNameExtractionMode.equalsIgnoreCase("regex")) {
						isoLanguage = extractIsoFromFileName(context, originalFileName);
					} else if (fileNameExtractionMode.equalsIgnoreCase("textdetection")) {
						isoLanguage = detectLanguage(originalFileName);
					} else if (fileNameExtractionMode.equalsIgnoreCase("regexORtextdetection")) {
						if ((isoLanguage = extractIsoFromFileName(context, originalFileName)) == null) {
							isoLanguage = detectLanguage(originalFileName);
						}
					} else if (fileNameExtractionMode.equalsIgnoreCase("textdetectionORregex")) {
						if ((isoLanguage = detectLanguage(originalFileName)) == null) {
							isoLanguage = extractIsoFromFileName(context, originalFileName);
						}
					}
					getLogger().info("End Processing Language, language is {}",
							new Object[] { ((isoLanguage != null) ? isoLanguage : "NULL") });

					/*
					 * Process ISO639Part1 to ISO639Part3 mapping
					 */
					if ((isoLanguage != null) && (isoLanguage.length() == 2) && (mappingIso639Part3 != null)) {
						isoLanguage = mappingIso639Part3.getProperty(isoLanguage);
					}
					if ((isoLanguage == null) || !allowedLanguages.contains(isoLanguage)) {
						throw new Exception("Invalid iso language : " + ((isoLanguage != null) ? isoLanguage : "NULL"));
					}

					attributes.put(OUTPUT_LANGUAGE, isoLanguage);
					attributes.put(SOURCE_FILENAME, originalFileName);
					attributes.put(SOURCE_MIME_TYPE, originalMimeType);
					attributes.put(SOURCE_UUID, originalUUID);
					int pageCount = 0;
					// String infoContent = originalFlowFile.getAttribute(SOURCE_FORMAT);
					String infoContent = attributes.get(SOURCE_FORMAT);
					// attributes.put(SOURCE_FORMAT, infoContent);

					if ((infoContent != null) && (StringUtils.equals(infoContent, pdfContent.getName()))) {
						getLogger().info("Start getting Pdf PageCount.");
						pageCount = PdfGsUtilities.getPdfPageCount(tmpProcessing);
						getLogger().info("End getting Pdf PageCount. pageCount : " + pageCount);
						if (pageCount > 0) {
							getLogger().info("Start converting Pdf => TIFF.");
							imgFile = PdfGsUtilities.convertPdf2Tiff(tmpProcessing);
							getLogger().info("End converting Pdf => TIFF.");
						} else {
							throw new Exception("Error document page count = 0");
						}
					} else {
						imgFile = tmpProcessing;
					}

					attributes.put(SOURCE_PAGE_COUNT, String.valueOf(pageCount));

					ITesseract instance = new Tesseract();
					instance.setLanguage(isoLanguage);
					instance.setDatapath(context.getProperty(TESSERACT_INSTALL_DIR)
							.evaluateAttributeExpressions(originalFlowFile).getValue());
					instance.setPageSegMode(Integer.parseInt(context.getProperty(TESSERACT_PAGE_SEG_MODE)
							.evaluateAttributeExpressions(originalFlowFile).getValue()));
					instance.setOcrEngineMode(Integer.parseInt(context.getProperty(TESSERACT_ENGINE_MODE)
							.evaluateAttributeExpressions(originalFlowFile).getValue()));

					BufferedImage imBuff = ImageIO.read(imgFile);
					getLogger().info("Start processing OCR.");
					String txt = instance.doOCR(imBuff);
					getLogger().info("End processing OCR.");

					FlowFile textFileFlow = session.create(originalFlowFile);
					try {
						textFileFlow = session.append(textFileFlow, new OutputStreamCallback() {
							@Override
							public void process(OutputStream out) throws IOException {
								getLogger().info("Start writing result to textFile.");
								IOUtils.copy(new ByteArrayInputStream(txt.getBytes("UTF-8")), out);
								getLogger().info("End writing result to textFile.");
							}
						});

						attributes.put(OUTPUT_OCR_UUID, textFileFlow.getAttribute(CoreAttributes.UUID.key()));
						txtFileAttributes.put(CoreAttributes.FILENAME.key(), originalFileName + ".txt");
						txtFileAttributes.put(CoreAttributes.MIME_TYPE.key(), textContent.getMimeType());
						txtFileAttributes.put(SOURCE_UUID, originalUUID);
						txtFileAttributes.put(SOURCE_FILENAME, originalFileName);
						txtFileAttributes.put(OUTPUT_IMG_UUID,
								originalFlowFile.getAttribute(CoreAttributes.UUID.key()));
						textFileFlow = session.putAllAttributes(textFileFlow, txtFileAttributes);
						outputFlowFileList.add(textFileFlow);
					} catch (Exception e) {
						getLogger().error("Error during processing text file result of OCR {}",
								new Object[] { textFileFlow, e });
						e.printStackTrace();
						invalidFlowFilesList.add(textFileFlow);
						throw e;
					}
				} catch (Exception e) {
					getLogger().error("Error during processing OCR {}", new Object[] { originalFlowFile, e });
					e.printStackTrace();
					invalidFlowFilesList.add(originalFlowFile);
				} finally {
					FileUtils.deleteQuietly(imgFile);
					FileUtils.deleteQuietly(tmpProcessing);
					FileUtils.deleteQuietly(textFile);
					getLogger().info("End Image Processing.");
				}
			}
		});

		try {
			if (invalidFlowFilesList.isEmpty()) {
				FlowFile originalFlowFileAppended = session.putAllAttributes(originalFlowFile, attributes);
				outputFlowFileList.add(originalFlowFileAppended);
			}
		} catch (Exception e) {
			getLogger().error("Error {}", new Object[] { originalFlowFile, e });
			e.printStackTrace();
			if (!invalidFlowFilesList.contains(originalFlowFile)) {
				invalidFlowFilesList.add(originalFlowFile);
			}
		}
		session.transfer(invalidFlowFilesList, REL_FAILURE);
		session.transfer(outputFlowFileList, REL_FILES);
		getLogger().info("File output queue 'files' have {} files: {}",
				new Object[] { outputFlowFileList.size(), outputFlowFileList });
		getLogger().info("File output queue 'failure' have {} files: {}",
				new Object[] { invalidFlowFilesList.size(), invalidFlowFilesList });
		getLogger().info("End Processing.");
	}

	private String extractIsoFromFileName(ProcessContext context, String txt) {
		String isoLanguage = null;
		/*
		 * Process regex extraction on filename
		 */
		final String strPatternFileExtract = context.getProperty(FILENAME_LANGUAGE_EXTRACTION_REGEX).getValue();
		final Pattern fileExtractPattern = strPatternFileExtract == null ? null
				: Pattern.compile(strPatternFileExtract);
		Matcher matcherLanguage = fileExtractPattern.matcher(txt);

		if (matcherLanguage.find()) {
			isoLanguage = matcherLanguage.group(1);
		}
		return isoLanguage;
	}

	private String detectLanguage(String text) {
		// TODO
		return ("eng");

	}

	@Override
	public Set<Relationship> getRelationships() {
		return this.relationships;
	}

	@Override
	public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return descriptors;
	}

}
