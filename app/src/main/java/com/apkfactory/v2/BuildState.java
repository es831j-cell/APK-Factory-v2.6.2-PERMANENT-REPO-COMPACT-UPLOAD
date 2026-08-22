package com.apkfactory.v2;

final class BuildState {
    static final String PREF = "apk_factory_state";
    static final String STATUS = "status";
    static final String PROGRESS = "progress";
    static final String LOG = "log";
    static final String ACTIVE = "build_active";
    static final String STAGE = "stage";
    static final String PROJECT_URI = "project_uri";
    static final String PROJECT_NAME = "project_name";
    static final String REQUESTED_REPO = "requested_repo";
    static final String LAST_REPO = "last_repo";
    static final String JOB_ID = "job_id";
    static final String PRIVATE_REPO = "private_repo";
    static final String OWNER = "owner";
    static final String REPO = "repo";
    static final String BRANCH = "branch";
    static final String REPO_URL = "repo_url";
    static final String COMMIT_SHA = "commit_sha";
    static final String WORKFLOW_ID = "workflow_id";
    static final String RUN_ID = "run_id";
    static final String OUTPUT_URI = "output_uri";
    static final String OUTPUT_NAME = "output_name";
    static final String FAILURE = "failure";
    static final String COMPLETION_PENDING = "completion_pending";

    static final int NEW = 0;
    static final int REPO_READY = 10;
    static final int COMMIT_READY = 20;
    static final int WORKFLOW_READY = 30;
    static final int RUN_READY = 40;
    static final int BUILD_SUCCEEDED = 50;
    static final int APK_SAVED = 60;
    static final int DONE = 100;

    private BuildState() {}
}
