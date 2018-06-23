import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.util.*
import org.apache.pdfbox.text.*
import org.apache.pdfbox.cos.COSName

def flowFile = session.get()
if(!flowFile) return

def s = new PDFTextStripper()
def doc, info
try {
	flowFile = session.write(flowFile, {inputStream, outputStream ->
		doc = PDDocument.load(inputStream)
		info = doc.getDocumentInformation()
	        s.writeText(doc, new OutputStreamWriter(outputStream))
	    } as StreamCallback
	)    
} finally {
	if( doc != null )
	{
	  doc.close();
	}      
}


flowFile = session.putAttribute(flowFile, 'pdf.page.count', "${doc.getNumberOfPages()}")
flowFile = session.putAttribute(flowFile, 'pdf.title', "${info.getTitle()}" )
flowFile = session.putAttribute(flowFile, 'pdf.author',"${info.getAuthor()}" );
flowFile = session.putAttribute(flowFile, 'pdf.subject', "${info.getSubject()}" );
flowFile = session.putAttribute(flowFile, 'pdf.keywords', "${info.getKeywords()}" );
flowFile = session.putAttribute(flowFile, 'pdf.creator', "${info.getCreator()}" );
flowFile = session.putAttribute(flowFile, 'pdf.producer', "${info.getProducer()}" );
flowFile = session.putAttribute(flowFile, 'pdf.date.creation', "${info.getCreationDate()}" );
flowFile = session.putAttribute(flowFile, 'pdf.date.modified', "${info.getModificationDate()}");
flowFile = session.putAttribute(flowFile, 'pdf.trapped', "${info.getTrapped()}" );  
session.transfer(flowFile, REL_SUCCESS)