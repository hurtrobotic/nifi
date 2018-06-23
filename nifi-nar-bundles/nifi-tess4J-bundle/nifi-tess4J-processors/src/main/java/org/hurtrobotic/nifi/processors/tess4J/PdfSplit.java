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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
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
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;


@Tags({ "pdfsplit" })
@EventDriven
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@CapabilityDescription("Perform a Split for each page of a PDF flowfile. ")
@WritesAttributes({
		@WritesAttribute(attribute = "file.source.split.filename", description = "The filename of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.split.uuid", description = "The UUID of the source FlowFile."),
		@WritesAttribute(attribute = "file.source.split.page.count", description = "The page count of the source FlowFile"),
		@WritesAttribute(attribute = "file.split.page", description = "Number of the current page") })

public class PdfSplit extends AbstractTesseractOcr {
	private static final String SOURCE_FILENAME = "file.source.split.filename";
	private static final String SOURCE_UUID = "file.source.split.uuid";
	private static final String SOURCE_PAGE_COUNT = "file.source.split.page.count";
	private static final String PAGEID = "file.split.page.count";

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

		// descriptors.add(FILENAME_LANGUAGE_EXTRACTION_MODE);
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
		List<FlowFile> originalFlowFileList = new ArrayList<>();
		List<FlowFile> invalidFlowFilesList = new ArrayList<>();

		try {
			session.read(originalFlowFile, new InputStreamCallback() {

				public void process(InputStream rawIn) throws IOException {
					PDDocument document = null;
					try {

						String originalFileName = originalFlowFile.getAttribute(CoreAttributes.FILENAME.key());
						document = PDDocument.load(rawIn);

						// Instantiating Splitter class
						Splitter splitter = new Splitter();

						// splitting the pages of a PDF document
						List<PDDocument> pages = splitter.split(document);

						// Creating an iterator
						Iterator<PDDocument> iterator = pages.listIterator();

						// Saving each page as an individual document
						int i=1;
						while (iterator.hasNext()) {
							PDDocument pd = iterator.next();

							FlowFile split = session.create(originalFlowFile);
							final Map<String, String> attributes = new HashMap<>();
							/*
							 * if (StringUtils.isNotBlank(originalFlowFile.getName())) {
							 * attributes.put(CoreAttributes.FILENAME.key(), originalFlowFile.getName()); }
							 */
							String parentUuid = originalFlowFile.getAttribute(CoreAttributes.UUID.key());
							String idx = String.format("%05d", i);
							String[] oriFileInfos = getFileInfos(originalFileName);
							String splittedName = (((oriFileInfos != null) && (oriFileInfos.length == 2))?(oriFileInfos[0] + "_" + idx + "." + oriFileInfos[1]):((oriFileInfos != null)?oriFileInfos[0] + "_" + idx +".pdf":((new Date()).getTime() + "_" + idx + ".pdf")));
							attributes.put(CoreAttributes.FILENAME.key(), splittedName);
							attributes.put(SOURCE_UUID, parentUuid);
							attributes.put(SOURCE_FILENAME, originalFileName);
							attributes.put(SOURCE_PAGE_COUNT, String.valueOf(pages.size()));
							attributes.put(PAGEID,String.valueOf(i));
							split = session.append(split, new OutputStreamCallback() {
								@Override
								public void process(OutputStream out) throws IOException {
									pd.save(out);
									pd.close();
								}
							});
							split = session.putAllAttributes(split, attributes);
							outputFlowFileList.add(split);		
							i++;
						}
						System.out.println("Multiple PDF’s created");
						document.close();
						document = null;
						originalFlowFileList.add(originalFlowFile);
					} catch (Exception e) {
						throw e;
					}
					finally {
						if (document != null) {
							try {
								document.close();
							} catch (Exception e) {
								getLogger().error("Error during closing PDF Document {}",
										new Object[] { originalFlowFile, e });
							}
						}
					}
				}
			});
		} catch (Exception e) {
			getLogger().error("Error during split PDF Document {}", new Object[] { originalFlowFile, e });
			invalidFlowFilesList.add(originalFlowFile);
            // Removing splits that may have been created
            session.remove(outputFlowFileList);
            // Removing the original flow from its list
            originalFlowFileList.remove(originalFlowFile);
		}

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
