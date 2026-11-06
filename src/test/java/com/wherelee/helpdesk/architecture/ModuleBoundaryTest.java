package com.wherelee.helpdesk.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModuleBoundaryTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.wherelee.helpdesk");

    @Test
    void wikiAndAiStayBelowTicketAndPlatform() {
        noClasses().that().resideInAPackage("..wiki..")
                .should().dependOnClassesThat().resideInAnyPackage("..ticket..", "..platform..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..ai..")
                .should().dependOnClassesThat().resideInAnyPackage("..ticket..", "..platform..")
                .check(CLASSES);
    }

    @Test
    void ticketDoesNotDependOnPlatform() {
        noClasses().that().resideInAPackage("..ticket..")
                .should().dependOnClassesThat().resideInAnyPackage("..platform..")
                .check(CLASSES);
    }

    @Test
    void tenantAndQuotaAreFoundationModules() {
        noClasses().that().resideInAPackage("..tenant..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..ticket..", "..wiki..", "..ai..", "..platform..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..quota..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..ticket..", "..wiki..", "..ai..", "..platform..")
                .check(CLASSES);
    }
}
