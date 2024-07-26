package edu.harvard.iq.dataverse.api;

import io.restassured.RestAssured;
import static io.restassured.RestAssured.given;
import static io.restassured.path.json.JsonPath.with;
import io.restassured.response.Response;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.BundleUtil;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import io.restassured.path.json.JsonPath;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;

public class DataversesIT {

    private static final Logger logger = Logger.getLogger(DataversesIT.class.getCanonicalName());

    @BeforeAll
    public static void setUpClass() {
        RestAssured.baseURI = UtilIT.getRestAssuredBaseUri();
    }
    
    @AfterAll
    public static void afterClass() {
        Response removeExcludeEmail = UtilIT.deleteSetting(SettingsServiceBean.Key.ExcludeEmailFromExport);
    }

    @Test
    public void testAttemptToCreateDuplicateAlias() throws Exception {

        Response createUser = UtilIT.createRandomUser();
//        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverse1Response = UtilIT.createRandomDataverse(apiToken);
        if (createDataverse1Response.getStatusCode() != 201) {
            // purposefully using println here to the error shows under "Test Results" in Netbeans
            System.out.println("A workspace for testing (a dataverse) couldn't be created in the root dataverse. The output was:\n\n" + createDataverse1Response.body().asString());
            System.out.println("\nPlease ensure that users can created dataverses in the root in order for this test to run.");
        } else {
            createDataverse1Response.prettyPrint();
        }
        assertEquals(201, createDataverse1Response.getStatusCode());

        String dataverseAlias1 = UtilIT.getAliasFromResponse(createDataverse1Response);
        String dataverseAlias2 = dataverseAlias1.toUpperCase();
        logger.info("Attempting to creating dataverse with alias '" + dataverseAlias2 + "' (uppercase version of existing '" + dataverseAlias1 + "' dataverse, should fail)...");
        String category = null;
        Response attemptToCreateDataverseWithDuplicateAlias = UtilIT.createDataverse(dataverseAlias2, category, apiToken);
        attemptToCreateDataverseWithDuplicateAlias.prettyPrint();
        assertEquals(403, attemptToCreateDataverseWithDuplicateAlias.getStatusCode());

        logger.info("Deleting dataverse " + dataverseAlias1);
        Response deleteDataverse1Response = UtilIT.deleteDataverse(dataverseAlias1, apiToken);
        deleteDataverse1Response.prettyPrint();
        assertEquals(200, deleteDataverse1Response.getStatusCode());

        logger.info("Checking response code for attempting to delete a non-existent dataverse.");
        Response attemptToDeleteDataverseThatShouldNotHaveBeenCreated = UtilIT.deleteDataverse(dataverseAlias2, apiToken);
        attemptToDeleteDataverseThatShouldNotHaveBeenCreated.prettyPrint();
        assertEquals(404, attemptToDeleteDataverseThatShouldNotHaveBeenCreated.getStatusCode());

    }

    @Test
    public void testDataverseCategory() {
        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseWithoutCategory = UtilIT.createRandomDataverse(apiToken);
        createDataverseWithoutCategory.prettyPrint();
        createDataverseWithoutCategory.then().assertThat()
                .body("data.dataverseType", equalTo("UNCATEGORIZED"))
                .statusCode(Status.CREATED.getStatusCode());

        String alias1 = UtilIT.getRandomDvAlias();
        String category1 = Dataverse.DataverseType.DEPARTMENT.toString();
        Response createDataverseWithCategory = UtilIT.createDataverse(alias1, category1, apiToken);
        createDataverseWithCategory.prettyPrint();
        createDataverseWithCategory.then().assertThat()
                .body("data.dataverseType", equalTo("DEPARTMENT"))
                .statusCode(Status.CREATED.getStatusCode());

        String alias2 = UtilIT.getRandomDvAlias();
        String madeUpCategory = "madeUpCategory";
        Response createDataverseWithInvalidCategory = UtilIT.createDataverse(alias2, madeUpCategory, apiToken);
        createDataverseWithInvalidCategory.prettyPrint();
        createDataverseWithInvalidCategory.then().assertThat()
                .body("data.dataverseType", equalTo("UNCATEGORIZED"))
                .statusCode(Status.CREATED.getStatusCode());

        String alias3 = UtilIT.getRandomDvAlias();
        String category3 = Dataverse.DataverseType.LABORATORY.toString().toLowerCase();
        Response createDataverseWithLowerCaseCategory = UtilIT.createDataverse(alias3, category3, apiToken);
        createDataverseWithLowerCaseCategory.prettyPrint();
        createDataverseWithLowerCaseCategory.then().assertThat()
                .body("data.dataverseType", equalTo("UNCATEGORIZED"))
                .statusCode(Status.CREATED.getStatusCode());

    }

    @Test
    public void testMinimalDataverse() throws FileNotFoundException {
        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);
        JsonObject dvJson;
        FileReader reader = new FileReader("doc/sphinx-guides/source/_static/api/dataverse-minimal.json");
        dvJson = Json.createReader(reader).readObject();
        Response create = UtilIT.createDataverse(dvJson, apiToken);
        create.prettyPrint();
        create.then().assertThat().statusCode(CREATED.getStatusCode());
        Response deleteDataverse = UtilIT.deleteDataverse("science", apiToken);
        deleteDataverse.prettyPrint();
        deleteDataverse.then().assertThat().statusCode(OK.getStatusCode());
    }
    
    
    @Test
    public void testGetDataverseOwners() throws FileNotFoundException {
        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);
        Response createDataverse1Response = UtilIT.createRandomDataverse(apiToken);
        
        createDataverse1Response.prettyPrint();
        createDataverse1Response.then().assertThat().statusCode(CREATED.getStatusCode());
        
        String first = UtilIT.getAliasFromResponse(createDataverse1Response);
        
        Response getWithOwnersFirst = UtilIT.getDataverseWithOwners(first, apiToken, true);
        getWithOwnersFirst.prettyPrint();
        
        Response createLevel1a = UtilIT.createSubDataverse(UtilIT.getRandomDvAlias() + "-level1a", null, apiToken, first);
        createLevel1a.prettyPrint();
        String level1a = UtilIT.getAliasFromResponse(createLevel1a);
        
        Response getWithOwners = UtilIT.getDataverseWithOwners(level1a, apiToken, true);
        getWithOwners.prettyPrint();
        
        getWithOwners.then().assertThat().body("data.isPartOf.identifier", equalTo(first));
        
    }

    /**
     * A regular user can create a Dataverse Collection and access its
     * GuestbookResponses by DV alias or ID.
     * A request for a non-existent Dataverse's GuestbookResponses returns
     * Not Found.
     * A regular user cannot access the guestbook responses for a Dataverse
     * that they do not have permissions for, like the root Dataverse.
     */
    @Test
    public void testGetGuestbookResponses() {
        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response create = UtilIT.createRandomDataverse(apiToken);
        create.prettyPrint();
        create.then().assertThat().statusCode(CREATED.getStatusCode());
        String alias = UtilIT.getAliasFromResponse(create);
        Integer dvId = UtilIT.getDataverseIdFromResponse(create);

        logger.info("Request guestbook responses for non-existent Dataverse");
        Response getResponsesByBadAlias = UtilIT.getGuestbookResponses("-1", null, apiToken);
        getResponsesByBadAlias.then().assertThat().statusCode(NOT_FOUND.getStatusCode());

        logger.info("Request guestbook responses for existent Dataverse by alias");
        Response getResponsesByAlias = UtilIT.getGuestbookResponses(alias, null, apiToken);
        getResponsesByAlias.then().assertThat().statusCode(OK.getStatusCode());

        logger.info("Request guestbook responses for existent Dataverse by ID");
        Response getResponsesById = UtilIT.getGuestbookResponses(dvId.toString(), null, apiToken);
        getResponsesById.then().assertThat().statusCode(OK.getStatusCode());

        logger.info("Request guestbook responses for root Dataverse by alias");
        getResponsesById = UtilIT.getGuestbookResponses("root", null, apiToken);
        getResponsesById.prettyPrint();
        getResponsesById.then().assertThat().statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    public void testNotEnoughJson() {
        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);
        Response createFail = UtilIT.createDataverse(Json.createObjectBuilder().add("name", "notEnough").add("alias", "notEnough").build(), apiToken);
        createFail.prettyPrint();
        createFail.then().assertThat()
                /**
                 * @todo We really don't want Dataverse to throw a 500 error
                 * when not enough JSON is supplied to create a dataverse.
                 */
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    //Ensure that email is not returned when the ExcludeEmailFromExport setting is set
    @Test 
    public void testReturnEmail() throws FileNotFoundException {        
        
        Response setToExcludeEmailFromExport = UtilIT.setSetting(SettingsServiceBean.Key.ExcludeEmailFromExport, "true");
        setToExcludeEmailFromExport.then().assertThat()
            .statusCode(OK.getStatusCode());
        
        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);
        String dataverseAlias = UtilIT.getRandomDvAlias();
        String emailAddressOfFirstDataverseContact = dataverseAlias + "@mailinator.com";
        JsonObjectBuilder jsonToCreateDataverse = Json.createObjectBuilder()
                .add("name", dataverseAlias)
                .add("alias", dataverseAlias)
                .add("dataverseContacts", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("contactEmail", emailAddressOfFirstDataverseContact)
                        )
                );
        ;

        Response createDataverseResponse = UtilIT.createDataverse(jsonToCreateDataverse.build(), apiToken);
        createDataverseResponse.prettyPrint();
        createDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());

        createDataverseResponse.then().assertThat()
                .statusCode(CREATED.getStatusCode())
                .body("data.alias", equalTo(dataverseAlias))
                .body("data.name", equalTo(dataverseAlias))
                .body("data.dataverseContacts[0].displayOrder", equalTo(0))
                .body("data.dataverseContacts[0].contactEmail", equalTo(emailAddressOfFirstDataverseContact))
                .body("data.permissionRoot", equalTo(true))
                .body("data.dataverseType", equalTo("UNCATEGORIZED"));
        
        Response exportDataverseAsJson = UtilIT.exportDataverse(dataverseAlias, apiToken);
        exportDataverseAsJson.prettyPrint();
        exportDataverseAsJson.then().assertThat()
                .statusCode(OK.getStatusCode());

        exportDataverseAsJson.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data.alias", equalTo(dataverseAlias))
                .body("data.name", equalTo(dataverseAlias))
                .body("data.dataverseContacts", equalTo(null))
                .body("data.permissionRoot", equalTo(true))
                .body("data.dataverseType", equalTo("UNCATEGORIZED"));

        RestAssured.unregisterParser("text/plain");

        List dataverseEmailNotAllowed = with(exportDataverseAsJson.body().asString())
                .getJsonObject("data.dataverseContacts");
        assertNull(dataverseEmailNotAllowed);
        
        Response removeExcludeEmail = UtilIT.deleteSetting(SettingsServiceBean.Key.ExcludeEmailFromExport);
        removeExcludeEmail.then().assertThat()
                .statusCode(200);
        
        Response exportDataverseAsJson2 = UtilIT.exportDataverse(dataverseAlias, apiToken);
        exportDataverseAsJson2.prettyPrint();
        exportDataverseAsJson2.then().assertThat()
                .statusCode(OK.getStatusCode());
        exportDataverseAsJson2.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data.alias", equalTo(dataverseAlias))
                .body("data.name", equalTo(dataverseAlias))
                .body("data.dataverseContacts[0].displayOrder", equalTo(0))
                .body("data.dataverseContacts[0].contactEmail", equalTo(emailAddressOfFirstDataverseContact))
                .body("data.permissionRoot", equalTo(true))
                .body("data.dataverseType", equalTo("UNCATEGORIZED"));
        
        RestAssured.unregisterParser("text/plain");
        List dataverseEmailAllowed = with(exportDataverseAsJson2.body().asString())
                .getJsonObject("data.dataverseContacts");
        assertNotNull(dataverseEmailAllowed);
        
        Response deleteDataverse2 = UtilIT.deleteDataverse(dataverseAlias, apiToken);
        deleteDataverse2.prettyPrint();
        deleteDataverse2.then().assertThat().statusCode(OK.getStatusCode());        
        Response deleteUserResponse = UtilIT.deleteUser(username);
        deleteUserResponse.prettyPrint();
        assertEquals(200, deleteUserResponse.getStatusCode());
    }
    
    /**
     * Test the Dataverse page error message and link 
     * when the query string has a malformed url
     */
    @Test
    public void testMalformedFacetQueryString(){
        
        Response createUser = UtilIT.createRandomUser();
        //        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverse1Response = UtilIT.createRandomDataverse(apiToken);
        if (createDataverse1Response.getStatusCode() != 201) {
            // purposefully using println here to the error shows under "Test Results" in Netbeans
            System.out.println("A workspace for testing (a dataverse) couldn't be created in the root dataverse. The output was:\n\n" + createDataverse1Response.body().asString());
            System.out.println("\nPlease ensure that users can created dataverses in the root in order for this test to run.");
        } else {
            createDataverse1Response.prettyPrint();
        }
        assertEquals(201, createDataverse1Response.getStatusCode());

        Integer dvId = UtilIT.getDataverseIdFromResponse(createDataverse1Response);
        String dvAlias = UtilIT.getAliasFromResponse(createDataverse1Response);

        Response publishDataverse = UtilIT.publishDataverseViaSword(dvAlias, apiToken);
        assertEquals(200, publishDataverse.getStatusCode());
      
        
        String expectedErrMsg = BundleUtil.getStringFromBundle("dataverse.search.facet.error", Arrays.asList("root"));

        // ----------------------------------
        // Malformed query string 1 
        //  - From 500 errs in log - No dataverse or dataverse.xhtml
        //  - expect "clear your search" url to link to root
        // ----------------------------------
        String badQuery1 = "/?q=&fq0=authorName_ss%25253A%252522U.S.+Department+of+Commerce%25252C+Bureau+of+the+Census%25252C+Geography+Division%252522&types=dataverses%25253Adatasets&sort=dateSort&order=desc";
        Response resp1 = given()
                        .get(badQuery1);
        
        String htmlStr = resp1.asString();        
        assertTrue(htmlStr.contains(expectedErrMsg));

        // ----------------------------------
        // Malformed query string 2 with Dataverse alias
        // - From https://github.com/IQSS/dataverse/issues/2605
        // - expect "clear your search" url to link to sub dataverse

        // ----------------------------------
        String badQuery2 = "/dataverse/" + dvAlias + "?fq0=authorName_ss:\"Bar,+Foo";
        Response resp2 = given()
                        .get(badQuery2);
        
        expectedErrMsg = BundleUtil.getStringFromBundle("dataverse.search.facet.error", Arrays.asList(dvAlias));

        String htmlStr2 = resp2.asString();        
        assertTrue(htmlStr2.contains(expectedErrMsg));
        
        
        // ----------------------------------
        // Malformed query string 3 with Dataverse alias
        // - expect "clear your search" url to link to sub dataverse
        // ----------------------------------
        String badQuery3 = "/dataverse/" + dvAlias + "?q=&fq0=authorName_ss%3A\"\"Finch%2C+Fiona\"&types=dataverses%3Adatasets&sort=dateSort&order=desc";
        Response resp3 = given()
                        .get(badQuery3);
        
        String htmlStr3 = resp3.asString();        

        expectedErrMsg = BundleUtil.getStringFromBundle("dataverse.search.facet.error", Arrays.asList(dvAlias));
        assertTrue(htmlStr3.contains(expectedErrMsg));

    
        // ----------------------------------
        // Malformed query string 4 with Dataverse id
        //  - expect "clear your search" url to link to root
        // ----------------------------------
        String badQuery4 = "/dataverse.xhtml?id=" + dvId + "&q=&fq0=authorName_ss%3A\"\"Finch%2C+Fiona\"&types=dataverses%3Adatasets&sort=dateSort&order=desc";
        Response resp4 = given()
                        .get(badQuery4);
        
        String htmlStr4 = resp4.asString();        
        System.out.println("------htmlStr4: " + resp4);

        // Solr searches using ?id={id} incorrectly searches the root
        //
        expectedErrMsg = BundleUtil.getStringFromBundle("dataverse.search.facet.error", Arrays.asList("root"));
        assertTrue(htmlStr4.contains(expectedErrMsg));

    }
    
    @Test
    public void testMoveDataverse() {
        Response createUser = UtilIT.createRandomUser();
        
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);
        
        Response superuserResponse = UtilIT.makeSuperUser(username);
        
        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        assertTrue(createDataverseResponse.prettyPrint().contains("isReleased\": false"));
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);
        Integer dataverseId = UtilIT.getDataverseIdFromResponse(createDataverseResponse);
        
        Response publishDataverse = UtilIT.publishDataverseViaNativeApi(dataverseAlias, apiToken);//.publishDataverseViaSword(dataverseAlias, apiToken);
        assertEquals(200, publishDataverse.getStatusCode());
        assertTrue(publishDataverse.prettyPrint().contains("isReleased\": true"));
        
        Response createDataverseResponse2 = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse2.prettyPrint();
        String dataverseAlias2 = UtilIT.getAliasFromResponse(createDataverseResponse2);
        Response publishDataverse2 = UtilIT.publishDataverseViaNativeApi(dataverseAlias2, apiToken);
        assertEquals(200, publishDataverse2.getStatusCode());
        
        Response moveResponse = UtilIT.moveDataverse(dataverseAlias, dataverseAlias2, true, apiToken);

        moveResponse.prettyPrint();
        moveResponse.then().assertThat().statusCode(OK.getStatusCode());
        
        // because indexing happens asynchronously, we'll wait first, and then retry a few times, before failing
        int numberofAttempts = 0;
        boolean checkIndex = true;
        while (checkIndex) {
            try {   
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException ex) {
                    }                
                Response search = UtilIT.search("id:dataverse_" + dataverseId + "&subtree=" + dataverseAlias2, apiToken);
                search.prettyPrint();
                search.then().assertThat()
                        .body("data.total_count", equalTo(1))
                        .statusCode(200);
                checkIndex = false;
            } catch (AssertionError ae) {
                if (numberofAttempts++ > 5) {
                    throw ae;
                }
            }
        }

    }

    // testCreateDeleteDataverseLink was here but is now in LinkIT

    @Test
    public void testUpdateDefaultContributorRole() {
        Response createUser = UtilIT.createRandomUser();

        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response superuserResponse = UtilIT.makeSuperUser(username);

        Response createUserRando = UtilIT.createRandomUser();

        createUserRando.prettyPrint();
        String apiTokenRando = UtilIT.getApiTokenFromResponse(createUserRando);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse.prettyPrint();
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        //Try no perms user
        Response updateDataverseDefaultRoleNoPerms = UtilIT.updateDefaultContributorsRoleOnDataverse(dataverseAlias, "curator", apiTokenRando);
        updateDataverseDefaultRoleNoPerms.prettyPrint();
        updateDataverseDefaultRoleNoPerms.then().assertThat()
                .statusCode(401);
        
        // try role with no dataset permissions alias
        Response updateDataverseDefaultRoleBadRolePermissions = UtilIT.updateDefaultContributorsRoleOnDataverse(dataverseAlias, "dvContributor", apiToken);
        updateDataverseDefaultRoleBadRolePermissions.prettyPrint();
        updateDataverseDefaultRoleBadRolePermissions.then().assertThat()
                .body("message", equalTo("Role dvContributor does not have dataset permissions."))
                .statusCode(400);

        //for test use an existing role. In practice this likely will be a custom role
        Response updateDataverseDefaultRole = UtilIT.updateDefaultContributorsRoleOnDataverse(dataverseAlias, "curator", apiToken);
        updateDataverseDefaultRole.prettyPrint();
        updateDataverseDefaultRole.then().assertThat()
                .body("data.message", equalTo("Default contributor role for Dataverse " + dataverseAlias + " has been set to Curator."))
                .statusCode(200);
        
        //for test use an existing role. In practice this likely will be a custom role
        Response updateDataverseDefaultRoleNone = UtilIT.updateDefaultContributorsRoleOnDataverse(dataverseAlias, "none", apiToken);
        updateDataverseDefaultRoleNone.prettyPrint();
        updateDataverseDefaultRoleNone.then().assertThat()
                .body("data.message", equalTo("Default contributor role for Dataverse " + dataverseAlias + " has been set to None."))
                .statusCode(200);

        // try bad role alias
        Response updateDataverseDefaultRoleBadRoleAlias = UtilIT.updateDefaultContributorsRoleOnDataverse(dataverseAlias, "colonel", apiToken);
        updateDataverseDefaultRoleBadRoleAlias.prettyPrint();
        updateDataverseDefaultRoleBadRoleAlias.then().assertThat()
                .body("message", equalTo("Role colonel not found."))
                .statusCode(404);

    }
    
    @Test
    public void testDataFileAPIPermissions() {

        Response createUser = UtilIT.createRandomUser();
        createUser.prettyPrint();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse.prettyPrint();
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        String pathToJsonFile = "src/test/resources/json/complete-dataset-with-files.json";
        Response createDatasetResponse = UtilIT.createDatasetViaNativeApi(dataverseAlias, pathToJsonFile, apiToken);
        
        //should fail if non-super user and attempting to
        //create a dataset with files
        createDatasetResponse.prettyPrint();
        createDatasetResponse.then().assertThat()
                .statusCode(BAD_REQUEST.getStatusCode());
        
        //should be ok to create a dataset without files...
        pathToJsonFile = "scripts/api/data/dataset-create-new.json";
        createDatasetResponse = UtilIT.createDatasetViaNativeApi(dataverseAlias, pathToJsonFile, apiToken);
        
        createDatasetResponse.prettyPrint();
        createDatasetResponse.then().assertThat()
                .statusCode(CREATED.getStatusCode());
        Integer datasetId = UtilIT.getDatasetIdFromResponse(createDatasetResponse);
        
        //As non-super user should be able to add a real file
        String pathToFile1 = "src/main/webapp/resources/images/cc0.png";
        Response authorAttemptsToAddFileViaNative = UtilIT.uploadFileViaNative(datasetId.toString(), pathToFile1, apiToken);

        authorAttemptsToAddFileViaNative.prettyPrint();
        authorAttemptsToAddFileViaNative.then().assertThat()
                .statusCode(OK.getStatusCode());
 
    }

    @Test
    public void testImportDDI() throws IOException, InterruptedException {

        Response createUser = UtilIT.createRandomUser();
        String username = UtilIT.getUsernameFromResponse(createUser);
        Response makeSuperUser = UtilIT.makeSuperUser(username);
        assertEquals(200, makeSuperUser.getStatusCode());
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        Response publishDataverse = UtilIT.publishDataverseViaNativeApi(dataverseAlias, apiToken);
        assertEquals(200, publishDataverse.getStatusCode());

        // This XML is a full DDI export without a PID.
        String xml = new String(Files.readAllBytes(Paths.get("doc/sphinx-guides/source/_static/api/ddi_dataset.xml")));

        Response importDDI = UtilIT.importDatasetDDIViaNativeApi(apiToken, dataverseAlias, xml,  null, "no");
        logger.info(importDDI.prettyPrint());
        assertEquals(201, importDDI.getStatusCode());

        // Under normal conditions, you shouldn't need to destroy these datasets.
        // Uncomment if they're still around from a previous failed run.
//        Response destroy1 = UtilIT.destroyDataset("doi:10.5072/FK2/ABCD11", apiToken);
//        destroy1.prettyPrint();
//        Response destroy2 = UtilIT.destroyDataset("doi:10.5072/FK2/ABCD22", apiToken);
//        destroy2.prettyPrint();

        Response importDDIPid = UtilIT.importDatasetDDIViaNativeApi(apiToken, dataverseAlias, xml,  "doi:10.5072/FK2/ABCD11", "no");
        logger.info(importDDIPid.prettyPrint());
        assertEquals(201, importDDIPid.getStatusCode());

        Response importDDIPidRel = UtilIT.importDatasetDDIViaNativeApi(apiToken, dataverseAlias, xml,  "doi:10.5072/FK2/ABCD22", "yes");
        logger.info(importDDIPidRel.prettyPrint());
        assertEquals(201, importDDIPidRel.getStatusCode());


        Response importDDIRelease = UtilIT.importDatasetDDIViaNativeApi(apiToken, dataverseAlias, xml, null, "yes");
        logger.info( importDDIRelease.prettyPrint());
        assertEquals(201, importDDIRelease.getStatusCode());

        Integer datasetIdInt = JsonPath.from(importDDI.body().asString()).getInt("data.id");

        Response search1 = UtilIT.search("id:dataset_" + datasetIdInt + "_draft", apiToken); // santity check, can find it
        search1.prettyPrint();
        search1.then().assertThat()
                .body("data.total_count", CoreMatchers.is(1))
                .body("data.count_in_response", CoreMatchers.is(1))
                .body("data.items[0].name", CoreMatchers.is("Replication Data for: Title"))
                .statusCode(OK.getStatusCode());

        Response search2 = UtilIT.search("id:dataset_" + datasetIdInt + "_draft", apiToken, "&geo_point=35,15&geo_radius=5"); // should find it
        search2.prettyPrint();
        search2.then().assertThat()
                .body("data.total_count", CoreMatchers.is(1))
                .body("data.count_in_response", CoreMatchers.is(1))
                .body("data.items[0].name", CoreMatchers.is("Replication Data for: Title"))
                .statusCode(OK.getStatusCode());

        Response search3 = UtilIT.search("id:dataset_" + datasetIdInt + "_draft", apiToken, "&geo_point=0,0&geo_radius=5"); // should not find it
        search3.prettyPrint();
        search3.then().assertThat()
                .body("data.total_count", CoreMatchers.is(0))
                .body("data.count_in_response", CoreMatchers.is(0))
                .body("data.items", Matchers.empty())
                .statusCode(OK.getStatusCode());

        //cleanup

        Response destroyDatasetResponse = UtilIT.destroyDataset(datasetIdInt, apiToken);
        assertEquals(200, destroyDatasetResponse.getStatusCode());

        Integer datasetIdIntPid = JsonPath.from(importDDIPid.body().asString()).getInt("data.id");
        Response destroyDatasetResponsePid = UtilIT.destroyDataset(datasetIdIntPid, apiToken);
        assertEquals(200, destroyDatasetResponsePid.getStatusCode());

        Integer datasetIdIntPidRel = JsonPath.from(importDDIPidRel.body().asString()).getInt("data.id");
        Response destroyDatasetResponsePidRel = UtilIT.destroyDataset(datasetIdIntPidRel, apiToken);
        assertEquals(200, destroyDatasetResponsePidRel.getStatusCode());
        
        UtilIT.sleepForDeadlock(UtilIT.MAXIMUM_IMPORT_DURATION);

        Integer datasetIdIntRelease = JsonPath.from(importDDIRelease.body().asString()).getInt("data.id");
        Response destroyDatasetResponseRelease = UtilIT.destroyDataset(datasetIdIntRelease, apiToken);
        assertEquals(200, destroyDatasetResponseRelease.getStatusCode());

        Response deleteDataverseResponse = UtilIT.deleteDataverse(dataverseAlias, apiToken);
        assertEquals(200, deleteDataverseResponse.getStatusCode());

        Response deleteUserResponse = UtilIT.deleteUser(username);
        assertEquals(200, deleteUserResponse.getStatusCode());
    }
    
    @Test
    public void testAttributesApi() throws Exception {

        Response createUser = UtilIT.createRandomUser();
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        if (createDataverseResponse.getStatusCode() != 201) {
            System.out.println("A workspace for testing (a dataverse) couldn't be created in the root dataverse. The output was:\n\n" + createDataverseResponse.body().asString());
            System.out.println("\nPlease ensure that users can created dataverses in the root in order for this test to run.");
        } else {
            createDataverseResponse.prettyPrint();
        }
        assertEquals(201, createDataverseResponse.getStatusCode());

        String collectionAlias = UtilIT.getAliasFromResponse(createDataverseResponse);
        String newCollectionAlias = collectionAlias + "RENAMED";
        
        // Change the alias of the collection: 
        
        Response changeAttributeResp = UtilIT.setCollectionAttribute(collectionAlias, "alias", newCollectionAlias, apiToken);
        changeAttributeResp.prettyPrint();
        
        changeAttributeResp.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("message.message", equalTo("Update successful"));
        
        // Check on the collection, under the new alias: 
        
        Response collectionInfoResponse = UtilIT.exportDataverse(newCollectionAlias, apiToken);
        collectionInfoResponse.prettyPrint();
        
        collectionInfoResponse.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data.alias", equalTo(newCollectionAlias));
        
        // Delete the collection (again, using its new alias):
        
        Response deleteCollectionResponse = UtilIT.deleteDataverse(newCollectionAlias, apiToken);
        deleteCollectionResponse.prettyPrint();
        assertEquals(OK.getStatusCode(), deleteCollectionResponse.getStatusCode());
    }

    @Test
    public void testListMetadataBlocks() {
        Response createUserResponse = UtilIT.createRandomUser();
        String apiToken = UtilIT.getApiTokenFromResponse(createUserResponse);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        Response setMetadataBlocksResponse = UtilIT.setMetadataBlocks(dataverseAlias, Json.createArrayBuilder().add("citation").add("astrophysics"), apiToken);
        setMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());

        String[] testInputLevelNames = {"geographicCoverage", "country", "city"};
        boolean[] testRequiredInputLevels = {false, true, false};
        boolean[] testIncludedInputLevels = {false, true, true};
        Response updateDataverseInputLevelsResponse = UtilIT.updateDataverseInputLevels(dataverseAlias, testInputLevelNames, testRequiredInputLevels, testIncludedInputLevels, apiToken);
        updateDataverseInputLevelsResponse.then().assertThat().statusCode(OK.getStatusCode());

        // Dataverse not found
        Response listMetadataBlocksResponse = UtilIT.listMetadataBlocks("-1", false, false, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(NOT_FOUND.getStatusCode());

        // Existent dataverse and no optional params
        String[] expectedAllMetadataBlockDisplayNames = {"Astronomy and Astrophysics Metadata", "Citation Metadata", "Geospatial Metadata"};

        listMetadataBlocksResponse = UtilIT.listMetadataBlocks(dataverseAlias, false, false, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());
        listMetadataBlocksResponse.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data[0].fields", equalTo(null))
                .body("data[1].fields", equalTo(null))
                .body("data[2].fields", equalTo(null))
                .body("data.size()", equalTo(3));

        String actualMetadataBlockDisplayName1 = listMetadataBlocksResponse.then().extract().path("data[0].displayName");
        String actualMetadataBlockDisplayName2 = listMetadataBlocksResponse.then().extract().path("data[1].displayName");
        String actualMetadataBlockDisplayName3 = listMetadataBlocksResponse.then().extract().path("data[2].displayName");
        assertNotEquals(actualMetadataBlockDisplayName1, actualMetadataBlockDisplayName2);
        assertNotEquals(actualMetadataBlockDisplayName1, actualMetadataBlockDisplayName3);
        assertNotEquals(actualMetadataBlockDisplayName2, actualMetadataBlockDisplayName3);
        assertThat(expectedAllMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName1));
        assertThat(expectedAllMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName2));
        assertThat(expectedAllMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName3));

        // Existent dataverse and onlyDisplayedOnCreate=true
        String[] expectedOnlyDisplayedOnCreateMetadataBlockDisplayNames = {"Citation Metadata", "Geospatial Metadata"};

        listMetadataBlocksResponse = UtilIT.listMetadataBlocks(dataverseAlias, true, false, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());
        listMetadataBlocksResponse.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data[0].fields", equalTo(null))
                .body("data[1].fields", equalTo(null))
                .body("data.size()", equalTo(2));

        actualMetadataBlockDisplayName1 = listMetadataBlocksResponse.then().extract().path("data[0].displayName");
        actualMetadataBlockDisplayName2 = listMetadataBlocksResponse.then().extract().path("data[1].displayName");
        assertNotEquals(actualMetadataBlockDisplayName1, actualMetadataBlockDisplayName2);
        assertThat(expectedOnlyDisplayedOnCreateMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName1));
        assertThat(expectedOnlyDisplayedOnCreateMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName2));

        // Existent dataverse and returnDatasetFieldTypes=true
        listMetadataBlocksResponse = UtilIT.listMetadataBlocks(dataverseAlias, false, true, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());
        listMetadataBlocksResponse.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data[0].fields", not(equalTo(null)))
                .body("data[1].fields", not(equalTo(null)))
                .body("data[2].fields", not(equalTo(null)))
                .body("data.size()", equalTo(3));

        actualMetadataBlockDisplayName1 = listMetadataBlocksResponse.then().extract().path("data[0].displayName");
        actualMetadataBlockDisplayName2 = listMetadataBlocksResponse.then().extract().path("data[1].displayName");
        actualMetadataBlockDisplayName3 = listMetadataBlocksResponse.then().extract().path("data[2].displayName");
        assertNotEquals(actualMetadataBlockDisplayName1, actualMetadataBlockDisplayName2);
        assertNotEquals(actualMetadataBlockDisplayName1, actualMetadataBlockDisplayName3);
        assertNotEquals(actualMetadataBlockDisplayName2, actualMetadataBlockDisplayName3);
        assertThat(expectedAllMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName1));
        assertThat(expectedAllMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName2));
        assertThat(expectedAllMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName3));

        // Check dataset fields for the updated input levels are retrieved
        int geospatialMetadataBlockIndex = actualMetadataBlockDisplayName1.equals("Geospatial Metadata") ? 0 : actualMetadataBlockDisplayName2.equals("Geospatial Metadata") ? 1 : 2;

        // Since the included property of geographicCoverage is set to false, we should retrieve the total number of fields minus one
        listMetadataBlocksResponse.then().assertThat()
                .body(String.format("data[%d].fields.size()", geospatialMetadataBlockIndex), equalTo(10));

        String actualMetadataField1 = listMetadataBlocksResponse.then().extract().path(String.format("data[%d].fields.geographicCoverage.name", geospatialMetadataBlockIndex));
        String actualMetadataField2 = listMetadataBlocksResponse.then().extract().path(String.format("data[%d].fields.country.name", geospatialMetadataBlockIndex));
        String actualMetadataField3 = listMetadataBlocksResponse.then().extract().path(String.format("data[%d].fields.city.name", geospatialMetadataBlockIndex));

        assertNull(actualMetadataField1);
        assertNotNull(actualMetadataField2);
        assertNotNull(actualMetadataField3);

        // Existent dataverse and onlyDisplayedOnCreate=true and returnDatasetFieldTypes=true
        listMetadataBlocksResponse = UtilIT.listMetadataBlocks(dataverseAlias, true, true, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());
        listMetadataBlocksResponse.then().assertThat()
                .statusCode(OK.getStatusCode())
                .body("data[0].fields", not(equalTo(null)))
                .body("data[1].fields", not(equalTo(null)))
                .body("data.size()", equalTo(2));

        actualMetadataBlockDisplayName1 = listMetadataBlocksResponse.then().extract().path("data[0].displayName");
        actualMetadataBlockDisplayName2 = listMetadataBlocksResponse.then().extract().path("data[1].displayName");
        assertNotEquals(actualMetadataBlockDisplayName1, actualMetadataBlockDisplayName2);
        assertThat(expectedOnlyDisplayedOnCreateMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName1));
        assertThat(expectedOnlyDisplayedOnCreateMetadataBlockDisplayNames, hasItemInArray(actualMetadataBlockDisplayName2));

        // Check dataset fields for the updated input levels are retrieved
        geospatialMetadataBlockIndex = actualMetadataBlockDisplayName2.equals("Geospatial Metadata") ? 1 : 0;

        listMetadataBlocksResponse.then().assertThat()
                .body(String.format("data[%d].fields.size()", geospatialMetadataBlockIndex), equalTo(1));

        actualMetadataField1 = listMetadataBlocksResponse.then().extract().path(String.format("data[%d].fields.geographicCoverage.name", geospatialMetadataBlockIndex));
        actualMetadataField2 = listMetadataBlocksResponse.then().extract().path(String.format("data[%d].fields.country.name", geospatialMetadataBlockIndex));
        actualMetadataField3 = listMetadataBlocksResponse.then().extract().path(String.format("data[%d].fields.city.name", geospatialMetadataBlockIndex));

        assertNull(actualMetadataField1);
        assertNotNull(actualMetadataField2);
        assertNull(actualMetadataField3);

        // User has no permissions on the requested dataverse
        Response createSecondUserResponse = UtilIT.createRandomUser();
        String secondApiToken = UtilIT.getApiTokenFromResponse(createSecondUserResponse);

        createDataverseResponse = UtilIT.createRandomDataverse(secondApiToken);
        createDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());
        String secondDataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        listMetadataBlocksResponse = UtilIT.listMetadataBlocks(secondDataverseAlias, true, true, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(UNAUTHORIZED.getStatusCode());
    }

    @Test
    public void testFeatureDataverse() throws Exception {

        Response createUser = UtilIT.createRandomUser();
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        Response publishDataverse = UtilIT.publishDataverseViaNativeApi(dataverseAlias, apiToken);
        assertEquals(200, publishDataverse.getStatusCode());


        Response createSubDVToBeFeatured = UtilIT.createSubDataverse(UtilIT.getRandomDvAlias() + "-feature", null, apiToken, dataverseAlias);
        String subDataverseAlias = UtilIT.getAliasFromResponse(createSubDVToBeFeatured);

        //publish a sub dataverse so that the owner will have something to feature
        Response createSubDVToBePublished = UtilIT.createSubDataverse(UtilIT.getRandomDvAlias() + "-pub", null, apiToken, dataverseAlias);
        assertEquals(201, createSubDVToBePublished.getStatusCode());
        String subDataverseAliasPub = UtilIT.getAliasFromResponse(createSubDVToBePublished);
        publishDataverse = UtilIT.publishDataverseViaNativeApi(subDataverseAliasPub, apiToken);
        assertEquals(200, publishDataverse.getStatusCode());

        //can't feature a dataverse that is unpublished
        Response featureSubDVResponseUnpublished = UtilIT.addFeaturedDataverse(dataverseAlias, subDataverseAlias, apiToken);
        featureSubDVResponseUnpublished.prettyPrint();
        assertEquals(400, featureSubDVResponseUnpublished.getStatusCode());
        featureSubDVResponseUnpublished.then().assertThat()
                .body(containsString("may not be featured"));

        //can't feature a dataverse you don't own
        Response featureSubDVResponseNotOwned = UtilIT.addFeaturedDataverse(dataverseAlias, "root", apiToken);
        featureSubDVResponseNotOwned.prettyPrint();
        assertEquals(400, featureSubDVResponseNotOwned.getStatusCode());
        featureSubDVResponseNotOwned.then().assertThat()
                .body(containsString("may not be featured"));

        //can't feature a dataverse that doesn't exist
        Response featureSubDVResponseNotExist = UtilIT.addFeaturedDataverse(dataverseAlias, "dummy-alias-sek-foobar-333", apiToken);
        featureSubDVResponseNotExist.prettyPrint();
        assertEquals(400, featureSubDVResponseNotExist.getStatusCode());
        featureSubDVResponseNotExist.then().assertThat()
                .body(containsString("Can't find dataverse collection"));

        publishDataverse = UtilIT.publishDataverseViaNativeApi(subDataverseAlias, apiToken);
        assertEquals(200, publishDataverse.getStatusCode());

        //once published it should work
        Response featureSubDVResponse = UtilIT.addFeaturedDataverse(dataverseAlias, subDataverseAlias, apiToken);
        featureSubDVResponse.prettyPrint();
        assertEquals(OK.getStatusCode(), featureSubDVResponse.getStatusCode());


        Response getFeaturedDataverseResponse = UtilIT.getFeaturedDataverses(dataverseAlias, apiToken);
        getFeaturedDataverseResponse.prettyPrint();
        assertEquals(OK.getStatusCode(), getFeaturedDataverseResponse.getStatusCode());
        getFeaturedDataverseResponse.then().assertThat()
                .body("data[0]", equalTo(subDataverseAlias));

        Response deleteFeaturedDataverseResponse = UtilIT.deleteFeaturedDataverses(dataverseAlias, apiToken);
        deleteFeaturedDataverseResponse.prettyPrint();

        assertEquals(OK.getStatusCode(), deleteFeaturedDataverseResponse.getStatusCode());
        deleteFeaturedDataverseResponse.then().assertThat()
                .body(containsString("Featured dataverses have been removed"));

        Response deleteSubCollectionResponse = UtilIT.deleteDataverse(subDataverseAlias, apiToken);
        deleteSubCollectionResponse.prettyPrint();
        assertEquals(OK.getStatusCode(), deleteSubCollectionResponse.getStatusCode());

        Response deleteSubCollectionPubResponse = UtilIT.deleteDataverse(subDataverseAliasPub, apiToken);
        deleteSubCollectionResponse.prettyPrint();
        assertEquals(OK.getStatusCode(), deleteSubCollectionPubResponse.getStatusCode());

        Response deleteCollectionResponse = UtilIT.deleteDataverse(dataverseAlias, apiToken);
        deleteCollectionResponse.prettyPrint();
        assertEquals(OK.getStatusCode(), deleteCollectionResponse.getStatusCode());
    }

    @Test
    public void testUpdateInputLevels() {
        Response createUserResponse = UtilIT.createRandomUser();
        String apiToken = UtilIT.getApiTokenFromResponse(createUserResponse);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        // Update valid input levels
        String[] testInputLevelNames = {"geographicCoverage", "country"};
        boolean[] testRequiredInputLevels = {true, false};
        boolean[] testIncludedInputLevels = {true, false};
        Response updateDataverseInputLevelsResponse = UtilIT.updateDataverseInputLevels(dataverseAlias, testInputLevelNames, testRequiredInputLevels, testIncludedInputLevels, apiToken);
        String actualInputLevelName = updateDataverseInputLevelsResponse.then().extract().path("data.inputLevels[0].datasetFieldTypeName");
        int geographicCoverageInputLevelIndex = actualInputLevelName.equals("geographicCoverage") ? 0 : 1;
        updateDataverseInputLevelsResponse.then().assertThat()
                .body(String.format("data.inputLevels[%d].include", geographicCoverageInputLevelIndex), equalTo(true))
                .body(String.format("data.inputLevels[%d].required", geographicCoverageInputLevelIndex), equalTo(true))
                .body(String.format("data.inputLevels[%d].include", 1 - geographicCoverageInputLevelIndex), equalTo(false))
                .body(String.format("data.inputLevels[%d].required", 1 - geographicCoverageInputLevelIndex), equalTo(false))
                .statusCode(OK.getStatusCode());
        String actualFieldTypeName1 = updateDataverseInputLevelsResponse.then().extract().path("data.inputLevels[0].datasetFieldTypeName");
        String actualFieldTypeName2 = updateDataverseInputLevelsResponse.then().extract().path("data.inputLevels[1].datasetFieldTypeName");
        assertNotEquals(actualFieldTypeName1, actualFieldTypeName2);
        assertThat(testInputLevelNames, hasItemInArray(actualFieldTypeName1));
        assertThat(testInputLevelNames, hasItemInArray(actualFieldTypeName2));

        // Update input levels with an invalid field type name
        String[] testInvalidInputLevelNames = {"geographicCoverage", "invalid1"};
        updateDataverseInputLevelsResponse = UtilIT.updateDataverseInputLevels(dataverseAlias, testInvalidInputLevelNames, testRequiredInputLevels, testIncludedInputLevels, apiToken);
        updateDataverseInputLevelsResponse.then().assertThat()
                .body("message", equalTo("Invalid dataset field type name: invalid1"))
                .statusCode(BAD_REQUEST.getStatusCode());

        // Update input levels with invalid configuration (field required but not included)
        testIncludedInputLevels = new boolean[]{false, false};
        updateDataverseInputLevelsResponse = UtilIT.updateDataverseInputLevels(dataverseAlias, testInputLevelNames, testRequiredInputLevels, testIncludedInputLevels, apiToken);
        updateDataverseInputLevelsResponse.then().assertThat()
                .body("message", equalTo(BundleUtil.getStringFromBundle("dataverse.inputlevels.error.cannotberequiredifnotincluded", List.of("geographicCoverage"))))
                .statusCode(BAD_REQUEST.getStatusCode());

        // Update invalid empty input levels
        testInputLevelNames = new String[]{};
        updateDataverseInputLevelsResponse = UtilIT.updateDataverseInputLevels(dataverseAlias, testInputLevelNames, testRequiredInputLevels, testIncludedInputLevels, apiToken);
        updateDataverseInputLevelsResponse.then().assertThat()
                .body("message", equalTo("Error while updating dataverse input levels: Input level list cannot be null or empty"))
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @Test
    public void testAddDataverse() {
        Response createUser = UtilIT.createRandomUser();
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);
        String testAliasSuffix = "-add-dataverse";

        // Without optional input levels and facet ids
        String testDataverseAlias = UtilIT.getRandomDvAlias() + testAliasSuffix;
        Response createSubDataverseResponse = UtilIT.createSubDataverse(testDataverseAlias, null, apiToken, "root");
        createSubDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());
        Response listMetadataBlocksResponse = UtilIT.listMetadataBlocks(testDataverseAlias, false, false, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());
        String actualMetadataBlockName = listMetadataBlocksResponse.then().extract().path("data[0].name");
        assertEquals(actualMetadataBlockName, "citation");

        // With optional input levels and facet ids
        String[] testInputLevelNames = {"geographicCoverage", "country"};
        String[] testFacetIds = {"authorName", "authorAffiliation"};
        String[] testMetadataBlockNames = {"citation", "geospatial"};
        testDataverseAlias = UtilIT.getRandomDvAlias() + testAliasSuffix;
        createSubDataverseResponse = UtilIT.createSubDataverse(testDataverseAlias, null, apiToken, "root", testInputLevelNames, testFacetIds, testMetadataBlockNames);
        createSubDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());

        // Assert facets are configured
        Response listDataverseFacetsResponse = UtilIT.listDataverseFacets(testDataverseAlias, apiToken);
        String actualFacetName1 = listDataverseFacetsResponse.then().extract().path("data[0]");
        String actualFacetName2 = listDataverseFacetsResponse.then().extract().path("data[1]");
        assertNotEquals(actualFacetName1, actualFacetName2);
        assertThat(testFacetIds, hasItemInArray(actualFacetName1));
        assertThat(testFacetIds, hasItemInArray(actualFacetName2));

        // Assert input levels are configured
        Response listDataverseInputLevelsResponse = UtilIT.listDataverseInputLevels(testDataverseAlias, apiToken);
        String actualInputLevelName1 = listDataverseInputLevelsResponse.then().extract().path("data[0].datasetFieldTypeName");
        String actualInputLevelName2 = listDataverseInputLevelsResponse.then().extract().path("data[1].datasetFieldTypeName");
        assertNotEquals(actualFacetName1, actualFacetName2);
        assertThat(testInputLevelNames, hasItemInArray(actualInputLevelName1));
        assertThat(testInputLevelNames, hasItemInArray(actualInputLevelName2));

        // Assert metadata blocks are configured
        listMetadataBlocksResponse = UtilIT.listMetadataBlocks(testDataverseAlias, false, false, apiToken);
        listMetadataBlocksResponse.then().assertThat().statusCode(OK.getStatusCode());
        String actualMetadataBlockName1 = listMetadataBlocksResponse.then().extract().path("data[0].name");
        String actualMetadataBlockName2 = listMetadataBlocksResponse.then().extract().path("data[1].name");
        assertNotEquals(actualMetadataBlockName1, actualMetadataBlockName2);
        assertThat(testMetadataBlockNames, hasItemInArray(actualMetadataBlockName1));
        assertThat(testMetadataBlockNames, hasItemInArray(actualMetadataBlockName2));

        // Setting metadata blocks without citation
        testDataverseAlias = UtilIT.getRandomDvAlias() + testAliasSuffix;
        String[] testMetadataBlockNamesWithoutCitation = {"geospatial"};
        createSubDataverseResponse = UtilIT.createSubDataverse(testDataverseAlias, null, apiToken, "root", null, null, testMetadataBlockNamesWithoutCitation);
        createSubDataverseResponse.then().assertThat().statusCode(CREATED.getStatusCode());

        // Assert metadata blocks including citation are configured
        String[] testExpectedBlockNames = {"citation", "geospatial"};
        actualMetadataBlockName1 = listMetadataBlocksResponse.then().extract().path("data[0].name");
        actualMetadataBlockName2 = listMetadataBlocksResponse.then().extract().path("data[1].name");
        assertNotEquals(actualMetadataBlockName1, actualMetadataBlockName2);
        assertThat(testExpectedBlockNames, hasItemInArray(actualMetadataBlockName1));
        assertThat(testExpectedBlockNames, hasItemInArray(actualMetadataBlockName2));

        // Should return error when an invalid facet id is sent
        String invalidFacetId = "invalidFacetId";
        String[] testInvalidFacetIds = {"authorName", invalidFacetId};
        testDataverseAlias = UtilIT.getRandomDvAlias() + testAliasSuffix;
        createSubDataverseResponse = UtilIT.createSubDataverse(testDataverseAlias, null, apiToken, "root", testInputLevelNames, testInvalidFacetIds, testMetadataBlockNames);
        createSubDataverseResponse.then().assertThat()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", equalTo("Cant find dataset field type \"" + invalidFacetId + "\""));

        // Should return error when an invalid input level is sent
        String invalidInputLevelName = "wrongInputLevel";
        String[] testInvalidInputLevelNames = {"geographicCoverage", invalidInputLevelName};
        testDataverseAlias = UtilIT.getRandomDvAlias() + testAliasSuffix;
        createSubDataverseResponse = UtilIT.createSubDataverse(testDataverseAlias, null, apiToken, "root", testInvalidInputLevelNames, testFacetIds, testMetadataBlockNames);
        createSubDataverseResponse.then().assertThat()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", equalTo("Invalid dataset field type name: " + invalidInputLevelName));

        // Should return error when an invalid metadata block name is sent
        String invalidMetadataBlockName = "invalidMetadataBlockName";
        String[] testInvalidMetadataBlockNames = {"citation", invalidMetadataBlockName};
        testDataverseAlias = UtilIT.getRandomDvAlias() + testAliasSuffix;
        createSubDataverseResponse = UtilIT.createSubDataverse(testDataverseAlias, null, apiToken, "root", testInputLevelNames, testInvalidFacetIds, testInvalidMetadataBlockNames);
        createSubDataverseResponse.then().assertThat()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", equalTo("Invalid metadata block name: \"" + invalidMetadataBlockName + "\""));
    }
}
