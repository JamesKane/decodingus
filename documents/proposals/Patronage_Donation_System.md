# Patronage Donation System Proposal

## 1. Overview
DecodingUs is committed to operating as a free-to-use community service for genetic genealogy and population research. However, as the platform scales, the operational costs associated with hardware, hosting, and maintaining the DecodingUs Atmosphere will grow. To ensure long-term sustainability without compromising user privacy or monetizing user data, we propose implementing a Patronage Donation System.

## 2. Cost Analysis
Current projections estimate the operational "run rate" to be approximately **\$0.50 to \$1.00 per Personal Data Server (PDS) per month** hosted on the DecodingUs Atmosphere. 

As the user base expands, these costs will scale linearly. A community-funded model allows us to cover these expenses while maintaining our core values of openness and privacy.

## 3. Proposed Solution: Patronage System

We propose a tiered donation model ("Patron Levels") to incentivize contributions and recognize community supporters. This system must be secure, transparent, and integrated into the user's account management flow (potentially within the PDS dashboard).

### 3.1. Design Goals
*   **Sustainability:** Generate sufficient recurring revenue to cover hosting and hardware costs.
*   **Security:** Utilize established, secure payment processors (e.g., Stripe, PayPal) to handle transactions without storing sensitive financial data on our servers.
*   **Recognition:** Provide badges or visual indicators for supporters within the platform (e.g., on their profile or PDS dashboard).
*   **Voluntary:** Access to core features remains free; patronage is strictly optional.

### 3.2. Proposed Patron Levels (Monthly/Yearly)
*   **Supporter:** Covers the cost of ~1-2 PDS instances.
    *   *Suggested Amount:* \$2/month or \$20/year.
*   **Contributor:** Covers the cost of a small family cluster.
    *   *Suggested Amount:* \$5/month or \$50/year.
*   **Sustainer:** Significant contribution to hardware upgrades and development.
    *   *Suggested Amount:* \$10/month or \$100/year.
*   **Founding Patron:** Limited tier for early significant backers.
    *   *Suggested Amount:* \$50+/month.

### 3.3. Implementation Roadmap
1.  **Payment Gateway Integration:** Select and integrate a payment provider API (e.g., Stripe Checkout) into the Scala/Play backend.
2.  **Database Updates:** Add schema to track subscription status (active/inactive, tier, renewal date) linked to the user or PDS account. *Note: No credit card details stored.*
3.  **UI/UX:** Design a "Support DecodingUs" page and update the user dashboard to display Patron badges.
4.  **Accounting:** Automated invoice generation and transparency reports (optional) showing how funds are used (hosting vs. hardware).

## 4. Conclusion
By implementing a Patronage Donation System, DecodingUs can secure its financial future and infrastructure scalability while remaining true to its mission as a free resource for the genetic genealogy community.
