//! Transactional email. `Mailer::Logging` (default) logs instead of sending —
//! used in dev/CI; `Mailer::Ses` (feature `aws`) sends via Amazon SES v2.

use crate::error::ExternalError;

pub enum Mailer {
    /// Logs the message instead of sending (dev/test).
    Logging,
    #[cfg(feature = "aws")]
    Ses {
        client: aws_sdk_sesv2::Client,
        from: String,
    },
}

impl Mailer {
    pub fn logging() -> Self {
        Mailer::Logging
    }

    /// Build an SES mailer from the ambient AWS config (region/credentials from
    /// the environment, profile, or instance role).
    #[cfg(feature = "aws")]
    pub async fn ses(from: impl Into<String>) -> Self {
        let conf = aws_config::load_defaults(aws_config::BehaviorVersion::latest()).await;
        Mailer::Ses { client: aws_sdk_sesv2::Client::new(&conf), from: from.into() }
    }

    /// Send a plain-text transactional email.
    pub async fn send(&self, to: &str, subject: &str, body: &str) -> Result<(), ExternalError> {
        match self {
            Mailer::Logging => {
                tracing::info!(%to, %subject, "email (logging mailer) — body: {body}");
                Ok(())
            }
            #[cfg(feature = "aws")]
            Mailer::Ses { client, from } => {
                use aws_sdk_sesv2::types::{Body, Content, Destination, EmailContent, Message};
                let aws_err = |e: aws_sdk_sesv2::error::BuildError| ExternalError::Aws(e.to_string());
                let subject_c = Content::builder().data(subject).build().map_err(aws_err)?;
                let body_c = Content::builder().data(body).build().map_err(aws_err)?;
                let message = Message::builder()
                    .subject(subject_c)
                    .body(Body::builder().text(body_c).build())
                    .build();
                let content = EmailContent::builder().simple(message).build();
                client
                    .send_email()
                    .from_email_address(from)
                    .destination(Destination::builder().to_addresses(to).build())
                    .content(content)
                    .send()
                    .await
                    .map_err(|e| ExternalError::Aws(e.to_string()))?;
                tracing::debug!(%to, %subject, "email sent via SES");
                Ok(())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn logging_mailer_sends_ok() {
        let m = Mailer::logging();
        assert!(m.send("a@example.com", "Hi", "body").await.is_ok());
    }
}
