New OpenID Connect implementation including new log in scenarios (see [the guides](https://dataverse-guide--10905.org.readthedocs.build/en/10905/installation/oidc.html#choosing-provisioned-providers-at-log-in)) for the current JSF frontend, the new Single Page Application (SPA) frontend, and a generic API usage. The API scenario using Bearer Token authorization is illustrated with a Python script that can be found in the `doc/sphinx-guides/_static/api/bearer-token-example` directory. This Python script prompts you to log in to the Keycloak in a new browser window using selenium. You can run that script with the following commands:

```shell
    cd doc/sphinx-guides/_static/api/bearer-token-example
    ./run.sh
```

This script is safe for production use, as it does not require you to know the client secret or the user credentials. Therefore, you can safely distribute it as a part of your own Python script that lets users run some custom tasks.