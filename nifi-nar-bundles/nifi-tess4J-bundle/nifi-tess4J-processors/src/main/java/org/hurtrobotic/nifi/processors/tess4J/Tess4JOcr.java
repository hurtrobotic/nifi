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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

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

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;

@Tags({ "tesseractOcr", "ocr", "tess4J" })
@EventDriven
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@CapabilityDescription("Perform Tesseract OCR on Tiff flowfile. ")
@WritesAttributes({
		@WritesAttribute(attribute = "file.source.ocr.filename", description = "The filename of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.ocr.uuid", description = "The UUID of the source FlowFile."),
		@WritesAttribute(attribute = "mime.extension", description = "File extension of produced ocr FlowFile."),
		@WritesAttribute(attribute = "mime.type", description = "Mimetype of produced ocr FlowFile."),
		@WritesAttribute(attribute = "file.source.ocr.uuid", description = "The UUID of the source FlowFile."),
		@WritesAttribute(attribute = "output.ocr.language", description = "The language used by Tesseract OCR")})

public class Tess4JOcr extends AbstractTesseractOcr {
	private static final String SOURCE_FILENAME = "file.source.ocr.filename";
	private static final String SOURCE_UUID = "file.source.ocr.uuid";
	private static final String MIME_EXTENSION = "mime.extension";
	private static final String OUTPUT_LANGUAGE = "output.ocr.language";
	private List<PropertyDescriptor> descriptors;
	private Set<Relationship> relationships;


	public static final Relationship REL_ORI = new Relationship.Builder().name("original")
			.description("Original FlowFile processed").build();

	public static final Relationship REL_OCR = new Relationship.Builder().name("ocr")
			.description("Ocr Flowfile result").build();
	
	public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
			.description("Flowfiles that could not be processed").build();


	protected void init(final ProcessorInitializationContext context) {		
		super.init(context);
		Set<Relationship> relationships = new HashSet<Relationship>();
		List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
		getLogger().info("Start Init.");
		relationships.add(REL_ORI);
		relationships.add(REL_OCR);
		relationships.add(REL_FAILURE);
		this.relationships = Collections.unmodifiableSet(relationships);

		descriptors.add(FILENAME_LANGUAGE_EXTRACTION_MODE);
		descriptors.add(FILENAME_LANGUAGE_EXTRACTION_REGEX);
		descriptors.add(TESSERACT_OUTPUT_FORMAT);
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
		List<FlowFile> oriFlowFilesList = new ArrayList<>();

		Map<String, String> attributes = new HashMap<>();
		Map<String, String> outFileAttributes = new HashMap<>();

		session.read(originalFlowFile, new InputStreamCallback() {

			public void process(InputStream rawIn) throws IOException {


				try (final InputStream in = new BufferedInputStream(rawIn)) {
					getLogger().info("Start Image Processing.");
					System.setProperty("jna.encoding", "UTF8");
					String originalFileName = originalFlowFile.getAttribute(CoreAttributes.FILENAME.key());
					String originalUUID = originalFlowFile.getAttribute(CoreAttributes.UUID.key());
					ContentInfo textContent = ContentInfoUtil.findExtensionMatch("TXT");

					/*
					 * Retrieving Extraction Mode.
					 */
					String isoLanguage = null;
					final String fileNameExtractionMode = context.getProperty(FILENAME_LANGUAGE_EXTRACTION_MODE)
							.evaluateAttributeExpressions(originalFlowFile).getValue();

					getLogger().info("Processing language.");
					String defaultLanguage = context.getProperty(DEFAULT_LANGUAGE).evaluateAttributeExpressions(originalFlowFile).getValue();
					if (fileNameExtractionMode.equalsIgnoreCase("none")) {
						isoLanguage = defaultLanguage;
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
						getLogger().info("Requested Language \"{}\" is not valid iso language. using default processing Language  \"{}\"",
								new Object[] { isoLanguage, defaultLanguage});
						isoLanguage = defaultLanguage;
					}

					attributes.put(OUTPUT_LANGUAGE, isoLanguage);
					attributes.put(SOURCE_FILENAME, originalFileName);
					attributes.put(SOURCE_UUID, originalUUID);

					ITesseract instance = new Tesseract();
					instance.setLanguage(isoLanguage);
					instance.setDatapath(context.getProperty(TESSERACT_INSTALL_DIR)
							.evaluateAttributeExpressions(originalFlowFile).getValue());
					instance.setPageSegMode(Integer.parseInt(context.getProperty(TESSERACT_PAGE_SEG_MODE)
							.evaluateAttributeExpressions(originalFlowFile).getValue()));
					instance.setOcrEngineMode(Integer.parseInt(context.getProperty(TESSERACT_ENGINE_MODE)
							.evaluateAttributeExpressions(originalFlowFile).getValue()));

					BufferedImage imBuff = ImageIO.read(in);
					getLogger().info("Start processing OCR.");
					String txt = instance.doOCR(imBuff);
					getLogger().info("End processing OCR.");

					FlowFile outFileFlow = session.create(originalFlowFile);
					/**
					 * TODO IMPLEMENT PRODUCTION OF PDF DOCUMENT
					 */
					try {
						outFileFlow = session.append(outFileFlow, new OutputStreamCallback() {
							@Override
							public void process(OutputStream out) throws IOException {
								getLogger().info("Start writing result to outFile.");
								IOUtils.copy(new ByteArrayInputStream(txt.getBytes("UTF-8")), out);
								getLogger().info("End writing result to outFile.");
							}
						});
						String[] oriFileInfos = getFileInfos(originalFileName);
						String[] extensions = textContent.getFileExtensions();
						outFileAttributes.put(CoreAttributes.FILENAME.key(), oriFileInfos[0] + "." + extensions[0]);
						outFileAttributes.put(CoreAttributes.MIME_TYPE.key(), textContent.getMimeType());
						outFileAttributes.put(MIME_EXTENSION, "." + extensions[0]);						
						outFileAttributes.put(OUTPUT_LANGUAGE, isoLanguage);						
						outFileAttributes.put(SOURCE_UUID, originalUUID);
						outFileAttributes.put(SOURCE_FILENAME, originalFileName);
						outFileFlow = session.putAllAttributes(outFileFlow, outFileAttributes);
						outputFlowFileList.add(outFileFlow);
					} catch (Exception e) {
						getLogger().error("Error during processing text file result of OCR {}",
								new Object[] { outFileFlow, e });
						e.printStackTrace();
						throw e;
					}
					oriFlowFilesList.add(originalFlowFile);
				} catch (Exception e) {
					getLogger().error("Error during processing OCR {}", new Object[] { originalFlowFile, e });
					e.printStackTrace();
					invalidFlowFilesList.add(originalFlowFile);
				} finally {
					getLogger().info("End Image Processing.");
				}
			}
		});

		session.transfer(oriFlowFilesList, REL_ORI);
		session.transfer(invalidFlowFilesList, REL_FAILURE);
		session.transfer(outputFlowFileList, REL_OCR);
		getLogger().info("File output queue 'ori' have {} files: {}",
				new Object[] { oriFlowFilesList.size(), outputFlowFileList });		
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
		
		getLogger().info("File Language extraction regex : {}",
				new Object[] {strPatternFileExtract});	
		getLogger().info("File Language extraction, input text : {}",
				new Object[] {txt});		
		

		final Pattern fileExtractPattern = strPatternFileExtract == null ? null
				: Pattern.compile(strPatternFileExtract);
		Matcher matcherLanguage = fileExtractPattern.matcher(txt);

		if (matcherLanguage.find()) {
			getLogger().info("File Language extraction, regex find group extraction");					
			isoLanguage = matcherLanguage.group(1);
		}
		getLogger().info("File Language extraction, matcher group value : {}",
				new Object[] {isoLanguage});				
		return isoLanguage;
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
