package com.automation.dto.request;

import com.automation.dto.request.WishRespondRequest.WishResponseAction;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WishRespondRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_action_and_feedback_when_constructed() {
            WishRespondRequest request = new WishRespondRequest(WishResponseAction.ACCEPT, "looks good");

            assertThat(request.action()).isEqualTo(WishResponseAction.ACCEPT);
            assertThat(request.feedback()).isEqualTo("looks good");
        }

        @Test
        void should_allow_null_feedback_when_constructed() {
            WishRespondRequest request = new WishRespondRequest(WishResponseAction.CANCEL, null);

            assertThat(request.action()).isEqualTo(WishResponseAction.CANCEL);
            assertThat(request.feedback()).isNull();
        }

        @Test
        void should_support_all_enum_values() {
            assertThat(WishResponseAction.values())
                    .containsExactly(
                            WishResponseAction.ACCEPT,
                            WishResponseAction.REFINE,
                            WishResponseAction.CANCEL,
                            WishResponseAction.CLARIFY,
                            WishResponseAction.RETRY
                    );
        }

        @Test
        void should_resolve_enum_value_from_name() {
            assertThat(WishResponseAction.valueOf("REFINE")).isEqualTo(WishResponseAction.REFINE);
        }
    }

    @Nested
    class BeanValidation {

        @Test
        void should_pass_validation_when_action_is_set() {
            WishRespondRequest request = new WishRespondRequest(WishResponseAction.ACCEPT, "ok");

            Set<ConstraintViolation<WishRespondRequest>> violations = validator.validate(request);

            assertThat(violations).isEmpty();
        }

        @Test
        void should_pass_validation_when_feedback_is_null() {
            WishRespondRequest request = new WishRespondRequest(WishResponseAction.RETRY, null);

            Set<ConstraintViolation<WishRespondRequest>> violations = validator.validate(request);

            assertThat(violations).isEmpty();
        }

        @Test
        void should_fail_validation_when_action_is_null() {
            WishRespondRequest request = new WishRespondRequest(null, "feedback");

            Set<ConstraintViolation<WishRespondRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("action");
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_fields_match() {
            WishRespondRequest a = new WishRespondRequest(WishResponseAction.ACCEPT, "msg");
            WishRespondRequest b = new WishRespondRequest(WishResponseAction.ACCEPT, "msg");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_action_differs() {
            WishRespondRequest a = new WishRespondRequest(WishResponseAction.ACCEPT, "msg");
            WishRespondRequest b = new WishRespondRequest(WishResponseAction.REFINE, "msg");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_not_be_equal_when_feedback_differs() {
            WishRespondRequest a = new WishRespondRequest(WishResponseAction.ACCEPT, "msg-a");
            WishRespondRequest b = new WishRespondRequest(WishResponseAction.ACCEPT, "msg-b");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_fields_in_toString() {
            WishRespondRequest request = new WishRespondRequest(WishResponseAction.CLARIFY, "please clarify");

            assertThat(request.toString())
                    .contains("CLARIFY")
                    .contains("please clarify");
        }
    }
}
