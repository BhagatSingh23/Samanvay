/**
 * Schema translation subsystem — translates between the canonical domain
 * model and department-specific payload formats using schema mappings.
 *
 * <p>Key components:</p>
 * <ul>
 *   <li>{@link com.karnataka.fabric.adapters.translation.SchemaTranslatorService}
 *       — main service with cached mapping lookup</li>
 *   <li>{@link com.karnataka.fabric.adapters.translation.TransformEngine}
 *       — stateless engine for applying {@link com.karnataka.fabric.core.domain.FieldTransform} values</li>
 *   <li>{@link com.karnataka.fabric.adapters.translation.TranslationResult}
 *       — result DTO with success flag, translated payload, and warnings</li>
 * </ul>
 */
package com.karnataka.fabric.adapters.translation;
