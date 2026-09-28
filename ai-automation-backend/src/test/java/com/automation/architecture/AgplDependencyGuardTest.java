package com.automation.architecture;

import com.automation.entity.AuditTimestampListener;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: MySuricates is licensed under the AGPL-3.0, and AspectJ (aspectjweaver/aspectjrt,
 * EPL-2.0 without a GPL secondary license, plus Apache-1.1 parts) cannot be combined with it.
 * It came in only through spring-boot-starter-data-jpa → spring-aspects, which Spring Data JPA
 * auditing ({@code @EnableJpaAuditing}) requires. The pom excludes spring-aspects, and
 * {@link AuditTimestampListener} fills {@code @CreatedDate}/{@code @LastModifiedDate} instead
 * (TODO dc3d6101).
 *
 * <p>Fails when AspectJ is back on the classpath, when Spring Data auditing returns (it would not
 * start without spring-aspects, or pull it back in), or when an audited entity lacks the listener
 * and its timestamps would silently stay null.
 */
class AgplDependencyGuardTest {

    private static final String BASE_PACKAGE = "com.automation";

    /** One class from each AspectJ artifact and the Spring class that needs them. */
    private static final List<String> FORBIDDEN_CLASSES = List.of(
            "org.aspectj.lang.JoinPoint",                    // aspectjrt, also inside aspectjweaver
            "org.aspectj.weaver.loadtime.Agent",             // aspectjweaver
            "org.springframework.beans.factory.aspectj.AnnotationBeanConfigurerAspect"); // spring-aspects

    /** Compared by name: the class is woven with AspectJ and fails to load without spring-aspects. */
    private static final String SPRING_DATA_AUDITING_LISTENER =
            "org.springframework.data.jpa.domain.support.AuditingEntityListener";

    /** The entities that moved from Spring Data's listener to {@link AuditTimestampListener}. */
    private static final int MIN_AUDITED_ENTITIES = 21;

    @Test
    void aspectJIsNotOnTheClasspath() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(FORBIDDEN_CLASSES)
                .as("EPL-2.0 AspectJ is incompatible with the AGPL — see the exclusion in pom.xml")
                .noneMatch(name -> ClassUtils.isPresent(name, loader));
    }

    @Test
    void noConfigurationEnablesSpringDataAuditing() {
        assertThat(scan(EnableJpaAuditing.class))
                .as("@EnableJpaAuditing requires spring-aspects (and so AspectJ)")
                .isEmpty();
    }

    @Test
    void auditedEntitiesUseTheOwnListener() {
        List<String> violations = new ArrayList<>();
        int audited = 0;
        for (Class<?> entity : scan(Entity.class)) {
            List<Class<?>> listeners;
            try {
                listeners = listeners(entity);
            } catch (TypeNotPresentException | LinkageError e) {
                violations.add(entity.getSimpleName() + ": @EntityListeners not loadable (" + e.getMessage() + ")");
                continue;
            }
            if (listeners.stream().anyMatch(l -> l.getName().equals(SPRING_DATA_AUDITING_LISTENER))) {
                violations.add(entity.getSimpleName() + ": Spring Data's AuditingEntityListener");
            }
            List<Field> auditFields = fields(entity, CreatedDate.class, LastModifiedDate.class);
            if (auditFields.isEmpty()) {
                continue;
            }
            audited++;
            if (!listeners.contains(AuditTimestampListener.class)) {
                violations.add(entity.getSimpleName() + ": audit fields without @EntityListeners(AuditTimestampListener.class)");
            }
            for (Field field : auditFields) {
                if (field.getType() != Instant.class) {
                    violations.add(entity.getSimpleName() + "." + field.getName() + ": not an Instant");
                }
            }
            for (Field field : fields(entity, CreatedBy.class, LastModifiedBy.class)) {
                violations.add(entity.getSimpleName() + "." + field.getName() + ": @CreatedBy/@LastModifiedBy is not supported");
            }
        }

        assertThat(violations).isEmpty();
        assertThat(audited).isGreaterThanOrEqualTo(MIN_AUDITED_ENTITIES);
    }

    /** Found by AuditTimestampsIntegrationTest: set only at flush, whole seconds in SQLite. */
    @Test
    void noEntityUsesHibernateTimestampGeneration() {
        List<String> violations = new ArrayList<>();
        for (Class<?> entity : scan(Entity.class)) {
            for (Field field : fields(entity, CreationTimestamp.class, UpdateTimestamp.class)) {
                violations.add(entity.getSimpleName() + "." + field.getName());
            }
        }

        assertThat(violations)
                .as("use @CreatedDate/@LastModifiedDate with AuditTimestampListener")
                .isEmpty();
    }

    private static List<Class<?>> listeners(Class<?> entity) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            EntityListeners listeners = c.getAnnotation(EntityListeners.class);
            if (listeners != null) {
                for (Class<?> listener : listeners.value()) {
                    result.add(listener);
                }
            }
        }
        return result;
    }

    @SafeVarargs
    private static List<Field> fields(Class<?> entity, Class<? extends Annotation>... annotations) {
        List<Field> result = new ArrayList<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Arrays.stream(annotations).anyMatch(field::isAnnotationPresent)) {
                    result.add(field);
                }
            }
        }
        return result;
    }

    private static List<Class<?>> scan(Class<? extends Annotation> annotation) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            classes.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), AgplDependencyGuardTest.class.getClassLoader()));
        }
        return classes;
    }
}
