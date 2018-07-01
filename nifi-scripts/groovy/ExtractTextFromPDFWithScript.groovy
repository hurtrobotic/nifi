import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.util.*
import org.apache.pdfbox.text.*
import org.apache.pdfbox.cos.COSName
import org.apache.nifi.processor.io.*

def flowFile = session.get()
if(!flowFile) return
	def s = new PDFTextStripper()
def doc, info
def isError = false

try {
	// Cast a closure with an inputStream parameter to InputStreamCallback
	def read = session.read(flowFile, {inputStream ->
		try {
			doc = PDDocument.load(inputStream)
			info = doc.getDocumentInformation()
		} finally {
			if( doc != null ) {
				doc.close()
			}
		}
	} as InputStreamCallback)

	flowFile = session.putAttribute(flowFile, 'pdf.page.count', "${doc.getNumberOfPages()}")
	flowFile = session.putAttribute(flowFile, 'pdf.title', "${info.getTitle()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.author',"${info.getAuthor()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.subject', "${info.getSubject()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.keywords', "${info.getKeywords()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.creator', "${info.getCreator()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.producer', "${info.getProducer()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.date.creation', "${info.getCreationDate()}" )
	flowFile = session.putAttribute(flowFile, 'pdf.date.modified', "${info.getModificationDate()}")
	flowFile = session.putAttribute(flowFile, 'pdf.trapped', "${info.getTrapped()}" )
} catch(Exception e) {
	e.printStackTrace()
	isError = true
}

if (!isError) {
	session.transfer(flowFile, REL_SUCCESS)
} else {
	session.transfer(flowFile, REL_FAILURE)
}

