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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;

import org.apache.commons.io.FileUtils;
import net.sourceforge.tess4j.util.PdfGsUtilities;


@Tags({ "tiff", "converter", "tiffconverter", "tess4J" })
@EventDriven
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@CapabilityDescription("Perform a Tiff conversion of each page of a PDF flowfile. ")
@WritesAttributes({
		@WritesAttribute(attribute = "file.source.tiffconvert.filename", description = "The filename of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.tiffconvert.uuid", description = "The UUID of the source FlowFile.")})

public class TiffConverter extends AbstractTesseractOcr {
	private static final String SOURCE_FILENAME = "file.source.tiffconvert.filename";
	private static final String SOURCE_UUID = "file.source.tiffconvert.uuid";

	private List<PropertyDescriptor> descriptors;
	private Set<Relationship> relationships;

	public static final Relationship REL_FILES = new Relationship.Builder().name("files")
			.description("Each individual file will be routed to the files relationship").build();

	public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
			.description("Flowfiles that could not be processed").build();
	
	public static final Relationship REL_ORIGINAL = new Relationship.Builder().name("original")
			.description("The original file").build();
	
	protected void init(final ProcessorInitializationContext context) {
		getLogger().info("Start Init.");
		final Set<Relationship> relationships = new HashSet<Relationship>();
		final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();

		relationships.add(REL_FILES);
		relationships.add(REL_ORIGINAL);
		relationships.add(REL_FAILURE);

		this.relationships = Collections.unmodifiableSet(relationships);
		this.descriptors = Collections.unmodifiableList(descriptors);
		getLogger().info("End Init.");
	}

	public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
		getLogger().info("Start Processing.");
		FlowFile originalFlowFile = session.get();
		String originalFileName = originalFlowFile.getAttribute(CoreAttributes.FILENAME.key());
		String originalUUID = originalFlowFile.getAttribute(CoreAttributes.UUID.key());
		
		Map<String, String> tiffFileAttributes = new HashMap<>();
	
		List<FlowFile> outputFlowFileList = new ArrayList<>();
		List<FlowFile> originalFlowFileList = new ArrayList<>();
		List<FlowFile> invalidFlowFilesList = new ArrayList<>();

		session.read(originalFlowFile, new InputStreamCallback() {

			public void process(InputStream rawIn) throws IOException {
				try {
				
					FlowFile tiffFileFlow = session.create(originalFlowFile);
					tiffFileFlow = session.append(tiffFileFlow, new OutputStreamCallback() {
						@Override
						public void process(OutputStream out) throws IOException {
							File tmpProcessing = null;
							File imgFile = null;
							try {
								tmpProcessing = File.createTempFile("processing", "bin"); 
								FileOutputStream fos = new FileOutputStream(tmpProcessing);
								IOUtils.copy(rawIn, fos);
								fos.close();
								getLogger().info("Start writing result to textFile.");
								imgFile = PdfGsUtilities.convertPdf2Tiff(tmpProcessing);
								IOUtils.copy(new FileInputStream(imgFile), out);
								getLogger().info("End writing result to textFile.");
							} finally {
								FileUtils.deleteQuietly(imgFile);					
								FileUtils.deleteQuietly(tmpProcessing);							
							}
						}
					});
					String[] oriFileInfos = getFileInfos(originalFileName);
					ContentInfo tiffContent = ContentInfoUtil.findExtensionMatch(oriFileInfos[1]);
					tiffFileAttributes.put(CoreAttributes.FILENAME.key(), oriFileInfos[0] + "." + tiffContent.getFileExtensions()[0]);
					tiffFileAttributes.put(CoreAttributes.MIME_TYPE.key(), tiffContent.getMimeType());
					tiffFileAttributes.put(SOURCE_UUID, originalUUID);
					tiffFileAttributes.put(SOURCE_FILENAME, originalFileName);
					tiffFileFlow = session.putAllAttributes(tiffFileFlow, tiffFileAttributes);
					originalFlowFileList.add(originalFlowFile);
					outputFlowFileList.add(tiffFileFlow);						
				} catch (Exception e) {
					getLogger().error("Error during TIFF conversion of PDF Flowfile {}",
							new Object[] { originalFlowFile, e });
					invalidFlowFilesList.add(originalFlowFile);
		            // Removing splits that may have been created
		            session.remove(outputFlowFileList);			            
		            // Removing the original flow from its list
		            originalFlowFileList.remove(originalFlowFile);						
					throw e;
				} 	
			}
		});
		session.transfer(invalidFlowFilesList, REL_FAILURE);
		session.transfer(outputFlowFileList, REL_FILES);
		session.transfer(originalFlowFileList, REL_ORIGINAL);

		getLogger().info("File output queue 'files' have {} files: {}",
				new Object[] { outputFlowFileList.size(), outputFlowFileList });
		getLogger().info("File output queue 'originalFile' have {} files: {}",
				new Object[] { originalFlowFileList.size(), originalFlowFileList });

		getLogger().info("File output queue 'failure' have {} files: {}",
				new Object[] { invalidFlowFilesList.size(), invalidFlowFilesList });
		getLogger().info("End Processing.");
		
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
