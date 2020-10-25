package com.amazonaws.lambda.detection_ppe_grupo5;

import java.util.List;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.AmazonRekognitionException;
import com.amazonaws.services.rekognition.model.DetectProtectiveEquipmentRequest;
import com.amazonaws.services.rekognition.model.DetectProtectiveEquipmentResult;
import com.amazonaws.services.rekognition.model.EquipmentDetection;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.ProtectiveEquipmentBodyPart;
import com.amazonaws.services.rekognition.model.ProtectiveEquipmentPerson;
import com.amazonaws.services.rekognition.model.ProtectiveEquipmentSummarizationAttributes;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;

public class LambdaFunctionHandler implements RequestHandler<S3Event, String> {

	private AmazonS3 s3 = AmazonS3ClientBuilder.standard().build();

	public LambdaFunctionHandler() {
	}

	// Test purpose only.
	LambdaFunctionHandler(AmazonS3 s3) {
		this.s3 = s3;
	}

	@Override
	public String handleRequest(S3Event event, Context context) {
		context.getLogger().log("Received event: " + event);

		// Get the object from the event and show its content type
		String bucket = event.getRecords().get(0).getS3().getBucket().getName();
		String key = event.getRecords().get(0).getS3().getObject().getKey();
		try {
			S3Object response = s3.getObject(new GetObjectRequest(bucket, key));
			String contentType = response.getObjectMetadata().getContentType();
			context.getLogger().log("CONTENT TYPE: " + contentType);

			context.getLogger().log("IMAGE NAME: " + response.getKey());
			context.getLogger().log("CONTENT: " + response.getKey());
			ppeDetection(response.getKey(), bucket, context);
			return contentType;
		} catch (Exception e) {
			e.printStackTrace();
			context.getLogger().log(String.format("Error getting object %s from bucket %s. Make sure they exist and"
					+ " your bucket is in the same region as this function.", key, bucket));
			throw e;
		}

	}

	public static void ppeDetection(String photo, String bucket, Context context) {
		AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();

		ProtectiveEquipmentSummarizationAttributes summaryAttributes = new ProtectiveEquipmentSummarizationAttributes()
				.withMinConfidence(80F).withRequiredEquipmentTypes("FACE_COVER", "HAND_COVER");

		DetectProtectiveEquipmentRequest request = new DetectProtectiveEquipmentRequest()
				.withImage(new Image().withS3Object(
						new com.amazonaws.services.rekognition.model.S3Object().withName(photo).withBucket(bucket)))
				.withSummarizationAttributes(summaryAttributes);

		try {
			String bodyHTML = "<h1>PPE Detection Report</h1>\n"
					+ "<p>Here are the results of the PPE detection service for the image " + photo + "</p>\n"
					+ "<h2>Results</h2>\r\n";
			String ppeDetectionResult = "";
			ppeDetectionResult += "Detected PPE for people in image " + photo + "\n";
			ppeDetectionResult += "Detected people\n---------------------------" + "\n";
			DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);

			List<ProtectiveEquipmentPerson> persons = result.getPersons();
			if (persons.size() == 0) {
				bodyHTML += "<p><em>No persons detected</em></p>";
			}

			for (ProtectiveEquipmentPerson person : persons) {
				bodyHTML += "<h3>Detected person #" + person.getId() + "</h3>\n";
				ppeDetectionResult += "Detected person: " + person.getId() + "\n";
				List<ProtectiveEquipmentBodyPart> bodyParts = person.getBodyParts();
				if (bodyParts.isEmpty()) {
					ppeDetectionResult += "\tNo body parts detected" + "\n";
					bodyHTML += "<p><em>No body parts detected</em></p>";
				} else
					for (ProtectiveEquipmentBodyPart bodyPart : bodyParts) {
						bodyHTML += "<h4>" + bodyPart.getName() + "</h4>\r\n" + "<ul>\r\n" + "<li>Confidence: "
								+ bodyPart.getConfidence().toString() + "</li>\n";

						ppeDetectionResult += "\t" + bodyPart.getName() + ". Confidence: "
								+ bodyPart.getConfidence().toString() + "\n";

						List<EquipmentDetection> equipmentDetections = bodyPart.getEquipmentDetections();

						if (equipmentDetections.isEmpty()) {
							ppeDetectionResult += "\t\tNo PPE Detected on " + bodyPart.getName() + "\n";
							bodyHTML += "<p><em>No PPE Detected on " + bodyPart.getName() + "</em></p>\n </ul>";

						} else {
							for (EquipmentDetection item : equipmentDetections) {
								bodyHTML += "<li>" + item.getType() + ": "
										+ item.getCoversBodyPart().getValue().toString() + "\n" + "<ul>\n"
										+ "<li>Confidence: " + item.getCoversBodyPart().getConfidence().toString()
										+ "</li>\n" + "</ul>\n" + "</li>" + "</ul>";
								ppeDetectionResult += "\t\tItem: " + item.getType() + ". Confidence: "
										+ item.getConfidence().toString() + "\n";
								ppeDetectionResult += "\t\tCovers body part: "
										+ item.getCoversBodyPart().getValue().toString() + ". Confidence: "
										+ item.getCoversBodyPart().getConfidence().toString() + "\n";
							}
						}

					}
				bodyHTML += "<hr/>\n";
			}

			List<Integer> listWithRequired = result.getSummary().getPersonsWithRequiredEquipment();
			List<Integer> listWithoutRequired = result.getSummary().getPersonsWithoutRequiredEquipment();
			List<Integer> listIndeterminated = result.getSummary().getPersonsIndeterminate();

			bodyHTML += "<h2>Summary</h2>\n"
					+ "<h3><span class=\"hljs-attr\">Persons with required equipment</span></h3>\n"
					+ "<p><strong>Total: " + listWithRequired.size() + "</strong></p>";

			String idList = "";

			for (int i = 0; i < listWithRequired.size(); i++) {
				if (i != 0) {
					idList += ", " + listWithRequired.get(i);
				} else {
					idList += listWithRequired.get(i);
				}
			}

			if (listWithRequired.size() > 0) {
				bodyHTML += "<p><strong>IDs: " + idList + "</strong></p>\n";
			}

			bodyHTML += "\n\n<h3><span class=\"hljs-attr\">Persons without required equipment</span></h3>\n"
					+ "<p><strong>Total: " + listWithoutRequired.size() + "</strong></p>";

			idList = "";
			for (int i = 0; i < listWithoutRequired.size(); i++) {
				if (i != 0) {
					idList += ", " + listWithoutRequired.get(i);
				} else {
					idList += listWithoutRequired.get(i);
				}
			}

			if (listWithoutRequired.size() > 0) {
				bodyHTML += "<p><strong>IDs: " + idList + "</strong></p>\n";
			}

			bodyHTML += "\n\n<h3><span class=\"hljs-attr\">Indeterminated persons</span></h3>\n" + "<p><strong>Total: "
					+ listIndeterminated.size() + "</strong></p>";

			idList = "";
			for (int i = 0; i < listIndeterminated.size(); i++) {
				if (i != 0) {
					idList += ", " + listIndeterminated.get(i);
				} else {
					idList += listIndeterminated.get(i);
				}
			}

			if (listIndeterminated.size() > 0) {
				bodyHTML += "<p><strong>IDs: " + idList + "</strong></p>\n";
			}

			bodyHTML += "<hr />\n <h3 style=\"text-align: center;\"><strong>Thank you for using our service!</strong></h3>";

			context.getLogger().log("Person ID Summary\n-----------------" + "\n");

			ppeDetectionResult += DisplaySummary("With required equipment",
					result.getSummary().getPersonsWithRequiredEquipment(), context);
			ppeDetectionResult += DisplaySummary("Without required equipment",
					result.getSummary().getPersonsWithoutRequiredEquipment(), context);
			ppeDetectionResult += DisplaySummary("Indeterminate", result.getSummary().getPersonsIndeterminate(),
					context);

			String subject = "Detected PPE for people in image" + photo;

			send("saraodrada@gmail.com", "juanmanuelimbachi@hotmail.com", subject, ppeDetectionResult, bodyHTML,
					context);

		} catch (AmazonRekognitionException e) {
			e.printStackTrace();
		}
	}

	public static void send(String from, String to, String subject, String bodyText, String bodyHTML, Context context) {

		AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(Regions.US_WEST_2)
				.build();

		SendEmailRequest request = new SendEmailRequest().withDestination(new Destination().withToAddresses(to))
				.withMessage(new Message()
						.withBody(new Body().withHtml(new Content().withCharset("UTF-8").withData(bodyHTML))
								.withText(new Content().withCharset("UTF-8").withData(bodyText)))
						.withSubject(new Content().withCharset("UTF-8").withData(subject)))
				.withSource(from);

		client.sendEmail(request);
		context.getLogger().log("Email sent!");

	}

	public static String DisplaySummary(String summaryType, List<Integer> idList, Context context) {
		String summary = "";
		summary += summaryType + "\n\tIDs  " + "\n";

		if (idList.size() == 0) {
			summary += "None" + "\n";
		} else {
			int count = 0;
			for (Integer id : idList) {
				if (count++ == idList.size() - 1) {
					summary += id.toString() + "\n";
				} else {
					summary += id.toString() + ", ";
				}
			}
		}

		summary += "\n";
		return summary;

	}

}