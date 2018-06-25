package org.hurtrobotic.nifi.processors.tess4J;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;

import com.cybozu.labs.langdetect.Detector;
import com.cybozu.labs.langdetect.DetectorFactory;
import com.cybozu.labs.langdetect.LangDetectException;
import com.cybozu.labs.langdetect.Language;


public abstract class AbstractTesseractOcr extends AbstractProcessor {
	protected static final String PROP_MAPPING_ISO639 = File.separatorChar +"mapping_iso639.properties";
	protected static final String PROP_SUPPORTED_LGPACK = File.separatorChar +"supported_lgpack_iso639.properties";
	protected static final String PROPDIR_LANG_DETECT = File.separatorChar +"profiles" + File.separatorChar + "com.cybozu.labs";
	protected Properties mappingIso639Part3;
	protected Properties supportedLgPack;
	protected Detector detector;

	protected static final Set<String>allowedLanguages = new HashSet<String>( Arrays.asList("afr", "ara", "aze", "bel", "ben", "bul", "cat", "ces", "chi-sim", "chi-tra", "chr", "dan",
			"deu", "deu-frak", "dev", "ell", "eng", "enm", "epo", "equ", "est", "eus", "fin", "fra", "frk",
			"frm", "glg", "grc", "heb", "hin", "hrv", "hun", "ind", "isl", "ita", "ita-old", "jpn", "kan",
			"kor", "lav", "lit", "mal", "mkd", "mlt", "msa", "nld", "nor", "osd", "pol", "por", "ron", "rus",
			"slk", "slk-frak", "slv", "spa", "spa-old", "sqi", "srp", "swa", "swe", "tam", "tel", "tgl", "tha",
			"tur", "ukr", "vie"));
	
	public static final PropertyDescriptor FILENAME_LANGUAGE_EXTRACTION_MODE = new PropertyDescriptor.Builder()
			.name("filename.language.extraction.mode").displayName("language Iso extraction mode")
			.description("Mode used for extracting iso code from Flowfile filename. "
					+ "Valid values are regex textdetection regexORtextdetection textdetectionORregex.")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).defaultValue("none")
			.allowableValues("regex", "textdetection", "regexORtextdetection", "textdetectionORregex", "none")
			.required(true).build();

	public static final PropertyDescriptor FILENAME_LANGUAGE_EXTRACTION_REGEX = new PropertyDescriptor.Builder()
			.name("filename.language.extraction.regex").displayName("Language Iso code extraction (Regex)")
			.description("A Regular Expression that is matched against FlowFile filename. "
					+ "If an attribute match the regex group(1), the language value extracted is used for OCR. "
					+ "Iso supported format 639-3 (eng) or 639-2 (en), no case sensitive")
			.addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR).expressionLanguageSupported(true)
			.defaultValue(".*[-|_](\\w{2,3})[-|_]\\d{5}\\.\\w{3,4}").required(false).build();

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

	public static final PropertyDescriptor TESSERACT_OUTPUT_FORMAT = new PropertyDescriptor.Builder()
			.name("tesseract.output.format").displayName("Tesseract output format")
			.description("Output format for OCR production")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR) 
			.allowableValues("PDFDOC", "TXT")
			.defaultValue("PDFDOC").required(true).build();	
	
	public static final PropertyDescriptor DEFAULT_LANGUAGE = new PropertyDescriptor.Builder().name("default.language")
			.displayName("Default language")
			.description("Default language if detection failed or extraction mode set to none.")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).expressionLanguageSupported(true).defaultValue("eng")
			.allowableValues(allowedLanguages)
			.required(true).build();

	
	public String[] getFileInfos(String fileName) {
		String[] retour = null;
		final Pattern pattern = Pattern.compile("^(.*)\\.([^.]*)$");
		if (!StringUtils.isEmpty(fileName)) {
			Matcher matcher = pattern.matcher(fileName);
			if (matcher.find()) {
				if (matcher.groupCount() == 2) {
					retour = new String[] {matcher.group(1).trim(),matcher.group(2).trim()};
				} 
			}
		}
		return retour;
	}
	
    /**
     * Will load {@link Properties} from properties file discovered at the
     * provided path relative to the root of the classpath.
     */
    public Properties loadPropertiesFromClasspath(String path) {
        try {
            Properties prop = new Properties();
            prop.load(Class.class.getResourceAsStream(path));
            return prop;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }	
    
    protected void init(final ProcessorInitializationContext context) {		
		super.init(context);
		getLogger().info("Start Init.");
				  
		 try { 		 
			 mappingIso639Part3 = loadPropertiesFromClasspath(PROP_MAPPING_ISO639);
			 supportedLgPack = loadPropertiesFromClasspath(PROP_SUPPORTED_LGPACK);
			 SingletonDetectorFactory.getInstance();
		 }
		 catch (Exception e) { 
			 getLogger().error("Error during processing", new Object[] { context, e });		 
		 }
		 getLogger().info("End Init.");
    }
    
	protected String detectLanguage(String text) throws LangDetectException {	         
		String detectedLanguage = null;
		detector = DetectorFactory.create(); 
        detector.append(text);
        ArrayList<Language> probabilities = detector.getProbabilities();
        if (probabilities != null) {
	        for(int i=0;i<probabilities.size();i++) {
	        	String lg = probabilities.get(i).lang;
	        	String isoLg = mappingIso639Part3.getProperty(lg);
	        	String check = this.supportedLgPack.getProperty(isoLg);
	        	getLogger().info("Detect Language probabilities #{} : {} - {} - {}",new Object[] { Integer.valueOf(i), lg, isoLg, check });
	        	if (check.equalsIgnoreCase("yes")) {
	        		detectedLanguage = isoLg;
	        		break;
	        	}
	        }
        }
		getLogger().info("Detect Language is " + detectedLanguage);
        return detectedLanguage;
	}
    
	
	private static class SingletonDetectorFactory {
		private SingletonDetectorFactory() throws LangDetectException {
			String confDir = System.getenv("NIFI_HOME") + File.separatorChar + "conf";
			String profileDir = confDir + PROPDIR_LANG_DETECT;
			DetectorFactory.loadProfile(profileDir);
		}

		private static class SingletonHolder {
			private static SingletonDetectorFactory sessionData = null;	
			
			private synchronized static SingletonDetectorFactory getSessionData() throws LangDetectException {
				if (sessionData == null) {
					sessionData = new SingletonDetectorFactory();
				}
				return sessionData;				
			}
		}

		public static SingletonDetectorFactory getInstance() throws LangDetectException {
			return AbstractTesseractOcr.SingletonDetectorFactory.SingletonHolder.getSessionData();
		}
	}	
}
