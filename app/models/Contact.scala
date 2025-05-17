package models

import play.api.data.Form
import play.api.data.Forms.*

/**
 * Contains definitions and utilities for managing contact-related functionality, such as a contact form.
 */
object Contact {
  /**
   * Data Transfer Object (DTO) representing a contact form submission.
   *
   * @param name        The name of the individual submitting the contact request. This field is required and should not be empty.
   * @param email       The email address of the individual submitting the request. This field is required and should follow standard email format.
   * @param subject     The subject of the message. This field is required and serves as a title for the contact request.
   * @param message     The main content of the contact request. This field is required and provides detailed information.
   * @param phoneNumber A honeypot field used for spam prevention. Legitimate submissions should leave this field empty.
   */
  case class ContactDTO(
                         name: String,
                         email: String,
                         subject: String,
                         message: String,
                         phoneNumber: String // honeypot field
                       )

  /**
   * Companion object for the ContactDTO case class.
   *
   * Provides utility methods for working with instances of ContactDTO, including pattern matching
   * support via the custom unapply method. The unapply method allows deconstruction of a ContactDTO
   * instance into a tuple of its constituent fields - name, email, subject, message, and phoneNumber.
   */
  object ContactDTO {
    def unapply(dto: ContactDTO): Option[(String, String, String, String, String)] =
      Some((dto.name, dto.email, dto.subject, dto.message, dto.phoneNumber))
  }

  /**
   * Represents a form definition for capturing and validating contact form submissions.
   *
   * This form utilizes the Play Framework's `Form` and `mapping` DSL to define a structured input
   * schema for `ContactDTO`. Each field is validated based on its requirements:
   * - `name`: Must be non-empty and have a length between 1 and 64 characters.
   * - `email`: Must be a valid email address.
   * - `subject`: Must be non-empty and have a length between 1 and 64 characters.
   * - `message`: Must be non-empty and can have a maximum length of 2048 characters.
   * - `phoneNumber`: A honeypot field that should be left empty to avoid spam submissions.
   *
   * The `verifying` function adds a custom validation to check that the `phoneNumber` field is empty.
   * If the field is filled, it likely indicates spam, and the form submission will be marked invalid.
   */
  val form: Form[ContactDTO] = Form(
    mapping(
      "name" -> nonEmptyText(1, 64),
      "email" -> email,
      "subject" -> nonEmptyText(1, 64),
      "message" -> nonEmptyText(1, 2048),
      "phoneNumber" -> text // honeypot field
    )(ContactDTO.apply)(ContactDTO.unapply).verifying(
      "Invalid form submission",
      fields => fields match {
        case dto: ContactDTO => dto.phoneNumber.isEmpty // If honeypot is filled, it's probably spam
      }
    )
  )
}