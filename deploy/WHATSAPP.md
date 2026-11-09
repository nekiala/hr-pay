# WhatsApp: setting up the Meta account

HR Pay sends one-time codes and employee notifications through the WhatsApp Business Cloud API. The provider's number
(the "HR Pay number") is set up once under **System > WhatsApp** (`/platform/whatsapp`). A company can use its own
number instead, under **Company > Settings > WhatsApp** (`/company/whatsapp`). The steps are the same for both.

Pay figures never travel by WhatsApp: the payslip message only says it is ready and links to HR Pay.

## 1. What to collect in Meta Business Manager

| Field in HR Pay | Where it is in Meta |
| --- | --- |
| Phone number ID | WhatsApp Manager > Phone numbers > the number > "Phone number ID" (digits) |
| WhatsApp Business Account ID | WhatsApp Manager > Account tools > overview, "WhatsApp Business Account ID" (digits) |
| Number as people see it | The number itself, e.g. `+243 81 234 5678` |
| Access token (system user) | Business settings > Users > System users > add an **admin** system user, assign the app and the WhatsApp account (full control), then "Generate new token" with the permissions `whatsapp_business_messaging` and `whatsapp_business_management`. Choose "Never" for expiry. |
| App secret | developers.facebook.com > the app > App settings > Basic > "App secret" |

The token and the app secret are stored encrypted and never shown again; leaving them empty when saving keeps the
saved values.

## 2. Submit the templates

On the WhatsApp page, press **Submit to Meta**. HR Pay sends the ten templates below (five, each in French and
English). Press **Check the status** a few minutes later: Meta usually approves utility templates within minutes,
sometimes up to 24 hours. A message whose template is not approved yet is simply not sent.

The button links open `<public URL>/<page>`; the public URL is `PUBLIC_URL`.

### hrpay_otp (authentication)

Meta writes the text itself: "*123456* is your verification code. For your security, do not share this code. This code
expires in 5 minutes." with a **Copy code** button (French: "Copier le code").

### hrpay_payslip_available (utility): payslip available

- FR: Bonjour {{1}}, votre bulletin de paie est disponible dans HR Pay. Pour protéger vos données, il s'ouvre uniquement après connexion.
  Button: **Voir le bulletin**
- EN: Hello {{1}}, your payslip is available in HR Pay. To protect your data, it only opens after you sign in.
  Button: **View payslip**
- Example: {{1}} = Marie. Only the first name varies: Meta rejected an earlier version whose sentence was a parameter
  (INCORRECT_CATEGORY).

### hrpay_request_update (utility): decisions on leave, salary advances, certificates

- FR: Bonjour {{1}}, du nouveau sur votre demande : {{2}} Le détail est dans HR Pay. Button: **Ouvrir HR Pay**
- EN: Hello {{1}}, there is news on your request: {{2}} The details are in HR Pay. Button: **Open HR Pay**
- Example: {{2}} = "Votre congé du 3 au 7 novembre 2026 est approuvé."

### hrpay_action_needed (utility): requests to approve, letters to sign, documents expiring

- FR: Bonjour {{1}}, une action vous attend dans HR Pay : {{2}} Merci de vous connecter pour la traiter.
  Button: **Ouvrir HR Pay**
- EN: Hello {{1}}, something is waiting for you in HR Pay: {{2}} Please sign in to deal with it. Button: **Open HR Pay**
- Example: {{2}} = "Jean Mukendi demande un congé du 3 au 7 novembre 2026."

### hrpay_checkout_reminder (utility): forgot to check out

- FR: Bonjour {{1}}, {{2}} Pensez à pointer votre départ dans HR Pay. Button: **Pointer**
- EN: Hello {{1}}, {{2}} Remember to check out in HR Pay. Button: **Check out**
- Example: {{2}} = "Votre journée s'est terminée à 17:00."

## 3. Register the webhook

The WhatsApp page shows a **Callback URL** (`<public URL>/api/public/whatsapp/webhook`) and a **Verify token**. In the
Meta app: WhatsApp > Configuration > Webhook > Edit, paste both, then **Manage** and subscribe to the `messages` field.

The webhook tells HR Pay when a message is delivered or read (shown on the Messages page) and passes on replies: an
employee who answers STOP (or ARRÊT) stops receiving messages and gets a confirmation. Every call is checked against
the app secret (`X-Hub-Signature-256`). Meta needs a public HTTPS address, so the webhook does not work on a laptop
(`localhost`); sending does.

## 4. Turn it on for a company

Company > Settings > WhatsApp: tick **Send notifications to employees on WhatsApp** and choose what is sent. Each
employee then opens **My account > WhatsApp**, receives a code on WhatsApp at the number in their file, types it, and
agrees. The agreement and every withdrawal are kept (DRC Ordonnance-loi 23/010, Congo Loi 29-2019). A change of phone
number ends the agreement until the new number is confirmed.

## Before the database settings

The `.env` settings `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_TEMPLATE` and `WHATSAPP_LANGUAGE` still
work for one-time codes when nothing is saved on the platform page; the platform page takes over once it is on.
