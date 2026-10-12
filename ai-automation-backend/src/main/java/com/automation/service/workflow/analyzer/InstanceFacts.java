package com.automation.service.workflow.analyzer;

/**
 * What the running instance can do, for the rules that depend on it rather than on the definition text
 * (TODO 0ad8853b). Only the live analysis passes it; the gates over the shipped definitions analyze
 * without it, so their verdict does not depend on whether the machine running the tests has Docker.
 *
 * @param dockerReachable a container can start from this instance
 * @param dockerHint      why not and what to do, German; empty when reachable
 * @param dockerHintEn    the same in English
 */
public record InstanceFacts(boolean dockerReachable, String dockerHint, String dockerHintEn) {
}
