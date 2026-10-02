package co.edu.corhuila.opti.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the workflow service. */
@SpringBootApplication(scanBasePackages = "co.edu.corhuila.opti.workflow")
public class WorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
