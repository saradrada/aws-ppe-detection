package com.amazonaws.lambda.detection_ppe_grupo5;

import java.util.List;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.AmazonRekognitionException;
import com.amazonaws.services.rekognition.model.BoundingBox;
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
				.withMinConfidence(80F).withRequiredEquipmentTypes("FACE_COVER", "HAND_COVER", "HEAD_COVER");

		DetectProtectiveEquipmentRequest request = new DetectProtectiveEquipmentRequest()
				.withImage(new Image().withS3Object(
						new com.amazonaws.services.rekognition.model.S3Object().withName(photo).withBucket(bucket)))
				.withSummarizationAttributes(summaryAttributes);

		try {
			String ppeDetectionResult = "";
			ppeDetectionResult += "Detected PPE for people in image " + photo + "\n";
			ppeDetectionResult += "Detected people\n---------------------------" + "\n";
			DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);

			List<ProtectiveEquipmentPerson> persons = result.getPersons();

			for (ProtectiveEquipmentPerson person : persons) {
				ppeDetectionResult += "Detected person: " + person.getId() + "\n";
				List<ProtectiveEquipmentBodyPart> bodyParts = person.getBodyParts();
				if (bodyParts.isEmpty()) {
					ppeDetectionResult += "\tNo body parts detected" + "\n";
				} else
					for (ProtectiveEquipmentBodyPart bodyPart : bodyParts) {
						ppeDetectionResult += "\t" + bodyPart.getName() + ". Confidence: "
								+ bodyPart.getConfidence().toString() + "\n";

						List<EquipmentDetection> equipmentDetections = bodyPart.getEquipmentDetections();

						if (equipmentDetections.isEmpty()) {
							ppeDetectionResult += "\t\tNo PPE Detected on " + bodyPart.getName() + "\n";

						} else {
							for (EquipmentDetection item : equipmentDetections) {
								ppeDetectionResult += "\t\tItem: " + item.getType() + ". Confidence: "
										+ item.getConfidence().toString() + "\n";
								ppeDetectionResult += "\t\tCovers body part: "
										+ item.getCoversBodyPart().getValue().toString() + ". Confidence: "
										+ item.getCoversBodyPart().getConfidence().toString() + "\n";

								ppeDetectionResult += "\t\tBounding Box";
								BoundingBox box = item.getBoundingBox();

								ppeDetectionResult += "\t\tLeft: " + box.getLeft().toString() + "\n";
								ppeDetectionResult += "\t\tTop: " + box.getTop().toString() + "\n";
								ppeDetectionResult += "\t\tWidth: " + box.getWidth().toString() + "\n";
								ppeDetectionResult += "\t\tHeight: " + box.getHeight().toString() + "\n";
								ppeDetectionResult += "\t\tConfidence: " + item.getConfidence().toString() + "\n";
								System.out.println();
							}
						}

					}
			}
			context.getLogger().log("Person ID Summary\n-----------------" + "\n");

			// List<Integer> list=;
			ppeDetectionResult += DisplaySummary("With required equipment",
					result.getSummary().getPersonsWithRequiredEquipment(), context);
			ppeDetectionResult += DisplaySummary("Without required equipment",
					result.getSummary().getPersonsWithoutRequiredEquipment(), context);
			ppeDetectionResult += DisplaySummary("Indeterminate", result.getSummary().getPersonsIndeterminate(),
					context);

			//publishToTopic(ppeDetectionResult, "arn:aws:sns:us-west-2:682086073548:ppe_detection_topic_grupo5",context);
			String subject = "Detected PPE for people in image" + photo;
			String bodyHTML = "<h1>Amazon SES test (AWS SDK for Java)</h1>"
				      + "<p>This email was sent with <a href='https://aws.amazon.com/ses/'>"
				      + "Amazon SES</a> using the <a href='https://aws.amazon.com/sdk-for-java/'>" 
				      + "AWS SDK for Java</a>";
			send("saraodrada@gmail.com", "juanmanuelimbachi@hotmail.com", subject, ppeDetectionResult, bodyHTML, context);
		} catch (AmazonRekognitionException e) {
			e.printStackTrace();
		}
	}
	
	public static void send(String from, String to, String subject, String bodyText, String bodyHTML, Context context) {

		AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder
				.standard()
				.withRegion(Regions.US_WEST_2)
				.build();
		
		SendEmailRequest request = new SendEmailRequest()
				.withDestination(new Destination().withToAddresses(to))
				.withMessage(new Message().withBody(new Body().withHtml(new Content().withCharset("UTF-8").withData(bodyHTML))
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
//
//	public static void publishToTopic(String message, String topicArn, Context context) {
//
//		try {
//			SnsClient snsClient = SnsClient.builder().region(Region.US_WEST_2).build();
//
//			PublishRequest request = PublishRequest.builder().message(message).topicArn(topicArn).build();
//
//			PublishResponse result = snsClient.publish(request);
//			context.getLogger()
//					.log(result.messageId() + " Message sent. Status was " + result.sdkHttpResponse().statusCode());
//			context.getLogger().log(
//					result.messageId() + " PPE Detection results were sent " + result.sdkHttpResponse().statusCode());
//
//		} catch (SnsException e) {
//			System.err.println(e.awsErrorDetails().errorMessage());
//			System.exit(1);
//		}
//	}

}