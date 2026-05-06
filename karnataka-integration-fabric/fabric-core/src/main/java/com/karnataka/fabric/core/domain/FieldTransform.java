package com.karnataka.fabric.core.domain;

/**
 * Supported value transformations applied during field mapping between the
 * canonical domain model and a department-specific schema.
 *
 * <p>Each enum constant corresponds to a deterministic, stateless
 * transformation that the mapping engine applies to a single field value
 * (or, in the case of composite transforms like
 * {@link #SPLIT_FULLNAME_TO_FIRST_LAST}, produces multiple output fields).</p>
 */
public enum FieldTransform {

    /** Pass-through — no transformation applied. */
    NONE,

    /** Convert the string value to upper case. */
    UPPERCASE,

    /** Convert the string value to lower case. */
    LOWERCASE,

    /** Convert an ISO-8601 date/time string to Unix epoch milliseconds. */
    DATE_ISO_TO_EPOCH,

    /** Convert Unix epoch milliseconds to an ISO-8601 date/time string. */
    DATE_EPOCH_TO_ISO,

    /**
     * Split a full name string ("First Last") into separate
     * {@code firstName} and {@code lastName} fields.
     */
    SPLIT_FULLNAME_TO_FIRST_LAST,

    /**
     * Concatenate multiple address line fields into a single
     * composite address string.
     */
    CONCAT_ADDRESS_LINES
}
