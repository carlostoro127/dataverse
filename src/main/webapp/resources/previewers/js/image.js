var parentUrl = "";
$(document)
    .ready(
        function() {
          var wo = window.opener;
          if(wo!=null) {
            parentUrl = window.opener.location.href;
          }
          var queryParams = new URLSearchParams(
              window.location.search.substring(1));
          var fileUrl = queryParams.get("siteUrl")
              + "/api/access/datafile/"
              + queryParams.get("fileid") + "?gbrecs=false";
          var versionUrl = queryParams.get("siteUrl")
              + "/api/datasets/" + queryParams.get("datasetid")
              + "/versions/" + queryParams.get("datasetversion");
          var apiKey = queryParams.get("key");
          if (apiKey != null) {
            fileUrl = fileUrl + "&key=" + apiKey;
            versionUrl = versionUrl + "?key=" + apiKey;
          }
          $("#previewedImage").attr("src",fileUrl);
      $("#previewedImage")
    .wrap('<span style="display:inline-block"></span>')
    .css('display', 'block')
    .parent()
    .zoom({on:'grab'});
        });

function returnToDataset(parentUrl) {
  if (!window.opener) {
    //Opener is gone, just navigate to the dataset in this window
    window.location.assign(parentUrl);
  } else {
    //See if the opener is still showing the dataset
    try {
      if (window.opener.location.href === parentUrl) {
        //Yes - close the opener and reopen the dataset here (since just closing this window may not bring the opener to the front)
        window.opener.close();
        window.open(parentUrl, "_parent");
      } else {
      //No - so leave the opener alone and open the dataset here
        window.location.assign(parentUrl);
      }
    } catch (err) {
      //No, and the opener has navigated to some other site, so just open the dataset here  
      window.location.assign(parentUrl);
    }
  }
}


