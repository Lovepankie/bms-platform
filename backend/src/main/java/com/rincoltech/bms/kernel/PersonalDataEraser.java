package com.rincoltech.bms.kernel;

/**
 * One module's part of removing a closed business (issue #571, ADR-090): it deletes or anonymises the personal data in the
 * tables the module owns and keeps the money records. The platform calls every bean of this type in the one transaction of
 * the removal, bound to the business, after the operator confirmed it; a failure rolls the whole removal back. Lives in the
 * kernel so a module that does not depend on the platform (a vertical, the documents module) can take part without the core
 * naming it (chapter 5 section 5.4.3).
 */
public interface PersonalDataEraser {

    /** A short name for the removal's summary, for example {@code lending}. */
    String area();

    /** Erases this module's personal data of the bound business, in the caller's transaction; returns the rows touched. */
    int erase();
}
