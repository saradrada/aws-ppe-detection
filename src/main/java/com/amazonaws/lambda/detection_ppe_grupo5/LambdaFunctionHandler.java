package com.amazonaws.lambda.detection_ppe_grupo5;

import java.util.List;

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


public class LambdaFunctionHandler implements RequestHandler<S3Event, String> {

    private AmazonS3 s3 = AmazonS3ClientBuilder.standard().build();

    public LambdaFunctionHandler() {}

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
            context.getLogger().log(String.format(
                "Error getting object %s from bucket %s. Make sure they exist and"
                + " your bucket is in the same region as this function.", key, bucket));
            throw e;
        }
        
    }
    
    public static void ppeDetection(String photo, String bucket, Context context) {
        AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();
        
        ProtectiveEquipmentSummarizationAttributes summaryAttributes = new ProtectiveEquipmentSummarizationAttributes()
                .withMinConfidence(80F)
                .withRequiredEquipmentTypes("FACE_COVER", "HAND_COVER", "HEAD_COVER");
                
        DetectProtectiveEquipmentRequest request = new DetectProtectiveEquipmentRequest()
                .withImage(new Image()
                        .withS3Object(new com.amazonaws.services.rekognition.model.S3Object()
                                .withName(photo).withBucket(bucket)))
                .withSummarizationAttributes(summaryAttributes);

        try {
        	 context.getLogger().log("Detected PPE for people in image " + photo);
        	 context.getLogger().log("Detected people\n---------------");
            DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);


            List <ProtectiveEquipmentPerson> persons = result.getPersons();


            for (ProtectiveEquipmentPerson person: persons) {
            	 context.getLogger().log("ID: " + person.getId());
                List<ProtectiveEquipmentBodyPart> bodyParts=person.getBodyParts();
                if (bodyParts.isEmpty()){
                	 context.getLogger().log("\tNo body parts detected");
                } else
                    for (ProtectiveEquipmentBodyPart bodyPart: bodyParts) {
                    	 context.getLogger().log("\t" + bodyPart.getName() + ". Confidence: " + bodyPart.getConfidence().toString());



                        List<EquipmentDetection> equipmentDetections=bodyPart.getEquipmentDetections();

                        if (equipmentDetections.isEmpty()){
                        	 context.getLogger().log("\t\tNo PPE Detected on " + bodyPart.getName());

                        } 
                        else {
                            for (EquipmentDetection item: equipmentDetections) {
                            	 context.getLogger().log("\t\tItem: " + item.getType() + ". Confidence: " + item.getConfidence().toString());
                            	 context.getLogger().log("\t\tCovers body part: " 
                                        + item.getCoversBodyPart().getValue().toString() + ". Confidence: " + item.getCoversBodyPart().getConfidence().toString());

                            	 context.getLogger().log("\t\tBounding Box");
                                BoundingBox box =item.getBoundingBox();

                                context.getLogger().log("\t\tLeft: " +box.getLeft().toString());
                                context.getLogger().log("\t\tTop: " + box.getTop().toString());
                                context.getLogger().log("\t\tWidth: " + box.getWidth().toString());
                                context.getLogger().log("\t\tHeight: " + box.getHeight().toString());
                                context.getLogger().log("\t\tConfidence: " + item.getConfidence().toString());
                                System.out.println();
                            }
                        }

                    }
            }
            context.getLogger().log("Person ID Summary\n-----------------");
            
            //List<Integer> list=;
            DisplaySummary("With required equipment", result.getSummary().getPersonsWithRequiredEquipment(),context);
            DisplaySummary("Without required equipment", result.getSummary().getPersonsWithoutRequiredEquipment(),context);
            DisplaySummary("Indeterminate", result.getSummary().getPersonsIndeterminate(),context);         
       
            
        } catch(AmazonRekognitionException e) {
            e.printStackTrace();
        }
	}
	
    public static void DisplaySummary(String summaryType,List<Integer> idList, Context context)
    {
    	 context.getLogger().log(summaryType + "\n\tIDs  ");
        if (idList.size()==0) {
        	 context.getLogger().log("None");
        }
        else {
            int count=0;
            for (Integer id: idList ) { 
                if (count++ == idList.size()-1) {
                	 context.getLogger().log(id.toString());
                }
                else {
                	 context.getLogger().log(id.toString() + ", ");
                }
            }
        }
                    
        System.out.println();
        
    }
    
}