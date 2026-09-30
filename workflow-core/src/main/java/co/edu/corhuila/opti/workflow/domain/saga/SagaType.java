package co.edu.corhuila.opti.workflow.domain.saga;

/** The processes that touch more than one domain. */
public enum SagaType {
    PLACE_ORDER,
    CANCEL_ORDER
}
