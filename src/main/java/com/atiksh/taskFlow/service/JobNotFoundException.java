
package com.atiksh.taskFlow.service;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID id) {
        super("Job not found: " + id);
    }
}
