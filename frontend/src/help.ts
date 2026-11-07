// The help centre: short articles in French and English, each tied to the pages it explains, so the "?" in the top
// bar opens the right one. A video link can be added to an article once it is recorded.
import { language } from './i18n'

export type Article = {
  id: string
  /** Who it is for, to group the list. */
  audience: 'everyone' | 'managers' | 'hr' | 'payroll' | 'admin'
  /** Page paths it explains; the first match wins, the longest path first. */
  routes: string[]
  video?: string
  fr: { title: string; body: string }
  en: { title: string; body: string }
}

export const ARTICLES: Article[] = [
  {
    id: 'first-steps', audience: 'admin', routes: ['/setup', '/company'],
    fr: { title: 'Mettre en place une nouvelle entreprise', body: `
Le **guide de mise en place** (page d'accueil, ou Aide puis Mise en place) liste les étapes, dans l'ordre :

1. **Entreprise** : raison sociale, adresse, numéros CNSS et NIF, logo. Ils figurent sur les bulletins et les déclarations.
2. **Sites** : chaque lieu de travail, avec sa zone GPS ou ses adresses IP pour le pointage.
3. **Structure** : directions, départements et services, avec leurs responsables. Les approbations suivent cette structure.
4. **Horaires** et **jours fériés** de l'année.
5. **Grades** et **taux** : vérifiez-les avec votre comptable avant la première paie, puis cochez l'étape.
6. **Employés** : importez-les depuis Excel (Importer des données), ou ajoutez-les un par un.
7. **Première paie** : calculez-la, faites-la approuver par la Finance, puis clôturez le mois.

Les étapes de vérification (horaires, grades, taux, éléments de paie) se cochent à la main ; les autres se cochent seules quand les données sont là.` },
    en: { title: 'Setting up a new company', body: `
The **set-up guide** (home page, or Help then Set-up) lists the steps, in order:

1. **Company**: legal name, address, CNSS and tax numbers, logo. They appear on payslips and declarations.
2. **Sites**: each workplace, with its GPS area or IP addresses for check-in.
3. **Structure**: directions, departments and services, with their heads. Approvals follow this structure.
4. **Schedules** and the year's **holidays**.
5. **Grades** and **rates**: check them with your accountant before the first payroll, then tick the step.
6. **Employees**: import them from Excel (Import data), or add them one by one.
7. **First payroll**: calculate it, have Finance approve it, then close the month.

The review steps (schedules, grades, rates, pay elements) are ticked by hand; the others tick themselves once the data is there.` },
  },
  {
    id: 'check-in', audience: 'everyone', routes: ['/check-in'],
    fr: { title: 'Pointer son arrivée et son départ', body: `
Ouvrez **Pointer** et appuyez sur **Arrivée** en arrivant, **Départ** en partant. Le téléphone donne sa position si le site l'exige : autorisez la localisation pour HR Pay.

- **Hors connexion** : si votre site l'autorise, le pointage est gardé sur le téléphone et envoyé dès que la connexion revient (au plus 72 heures). Selon la règle du site, il est accepté seul ou vérifié par les RH.
- **Oubli ou erreur** : demandez une correction (menu Corrections) ; votre responsable la valide.
- **Sur le téléphone** : ajoutez HR Pay à l'écran d'accueil (Mon compte, Téléphone) pour l'ouvrir comme une application et recevoir les rappels.` },
    en: { title: 'Checking in and out', body: `
Open **Check in** and tap **In** when you arrive, **Out** when you leave. The phone gives its position when the site requires it: allow location for HR Pay.

- **Offline**: if your site allows it, the check-in is kept on the phone and sent as soon as the connection is back (within 72 hours). Depending on the site's rule, it is accepted as is or reviewed by HR.
- **Forgot or got it wrong**: ask for a correction (Corrections menu); your manager approves it.
- **On a phone**: add HR Pay to the home screen (My account, Phone) to open it like an app and get reminders.` },
  },
  {
    id: 'leave', audience: 'everyone', routes: ['/leave', '/missions'],
    fr: { title: 'Demander un congé ou une mission', body: `
Dans **Congés**, choisissez le type, les dates (une demi-journée est possible sur un jour) et joignez un document si on vous le demande. Votre solde est affiché avant l'envoi.

- La demande va au responsable de votre unité, ou à son adjoint s'il est absent.
- Vous êtes prévenu de la décision par une notification et par e-mail.
- Une **mission** se demande de la même façon dans **Missions** ; les jours de mission comptent comme travaillés.` },
    en: { title: 'Asking for leave or a mission', body: `
In **Leave**, choose the type and the dates (half a day is possible on a single day) and attach a document when asked. Your balance shows before you send.

- The request goes to your unit's head, or their deputy when they are away.
- You are told of the decision by a notification and by email.
- A **mission** is asked the same way in **Missions**; mission days count as worked.` },
  },
  {
    id: 'payslips', audience: 'everyone', routes: ['/my/payslips', '/my/year'],
    fr: { title: 'Lire son bulletin de paie', body: `
**Mes bulletins** montre chaque mois clôturé ; ouvrez-en un pour le détail ou le PDF.

- **Brut** : salaire de base, primes, heures supplémentaires approuvées et indemnités.
- **Retenues** : CNSS, IPR et vos remboursements de prêt.
- **Net à payer** : ce qui est versé sur votre compte ou par mobile money.

Une erreur ? Signalez-la aux RH : un bulletin clôturé n'est jamais modifié, il est complété par un bulletin de régularisation.` },
    en: { title: 'Reading your payslip', body: `
**My payslips** shows each closed month; open one for the details or the PDF.

- **Gross**: basic salary, bonuses, approved overtime and allowances.
- **Deductions**: CNSS, income tax (IPR) and your loan repayments.
- **Net to pay**: what is paid to your account or by mobile money.

A mistake? Tell HR: a closed payslip is never edited, it is completed by a correction payslip.` },
  },
  {
    id: 'documents', audience: 'everyone', routes: ['/my/documents'],
    fr: { title: 'Signer un document et demander une attestation', body: `
Dans **Mes documents** :

- **À signer** : lisez le PDF, demandez un code (envoyé par e-mail ou WhatsApp), saisissez-le avec votre nom complet. La copie signée est rangée dans votre dossier. Vous pouvez aussi refuser en donnant la raison.
- **Attestations** : demandez une attestation de travail ou de salaire ; vous êtes prévenu quand elle est prête.
- **Mon dossier** : les documents que les RH gardent pour vous, et ceux que vous leur envoyez.` },
    en: { title: 'Signing a document and asking for a certificate', body: `
In **My documents**:

- **To sign**: read the PDF, ask for a code (sent by email or WhatsApp), enter it with your full name. The signed copy goes into your file. You can also refuse and give the reason.
- **Certificates**: ask for an employment or salary certificate; you are told when it is ready.
- **My file**: the documents HR keeps for you, and the ones you send them.` },
  },
  {
    id: 'approvals', audience: 'managers', routes: ['/presence', '/overtime/approvals', '/corrections', '/evaluations'],
    fr: { title: 'Responsables : présence, approbations et évaluations', body: `
Le responsable d'une unité approuve pour toute son équipe, sous-unités comprises ; son adjoint seulement quand il est absent ou le poste vacant.

- **Présence** : qui est là, en retard, en congé ou en mission aujourd'hui.
- **Heures supplémentaires** : à approuver avant la paie, sinon elles ne sont pas payées.
- **Corrections** : les oublis de pointage signalés par l'équipe.
- **Évaluations** : vous notez, les RH approuvent, l'employé peut contester.` },
    en: { title: 'Managers: presence, approvals and evaluations', body: `
A unit's head approves for their whole team, sub-units included; their deputy only when they are away or the post is vacant.

- **Presence**: who is in, late, on leave or on mission today.
- **Overtime**: approve it before payroll, or it is not paid.
- **Corrections**: missed check-ins reported by the team.
- **Evaluations**: you score, HR approves, the employee can contest.` },
  },
  {
    id: 'people', audience: 'hr', routes: ['/users', '/roles', '/lifecycle', '/departures', '/imports'],
    fr: { title: 'RH : employés, rôles, embauches et départs', body: `
- **Utilisateurs** : créez la fiche, l'unité, le contrat et le salaire. Un mot de passe provisoire est changé à la première connexion.
- **Rôles** : chaque rôle est une liste de permissions. Les rôles sensibles (Administrateur, RH, Finance) demandent une deuxième étape à la connexion.
- **Embauches et départs** : les listes de contrôle, les documents à réunir, les lettres à signer.
- **Départ** : le décompte final (préavis, congés, indemnités) se calcule dans Départs ; le dernier bulletin le reprend.
- **Importer des données** : modèles Excel pour les employés, contrats, salaires, soldes de congés et cumuls de l'année. Rien n'est enregistré avant la vérification.` },
    en: { title: 'HR: employees, roles, hiring and departures', body: `
- **Users**: create the person, their unit, contract and salary. A temporary password is changed at the first sign-in.
- **Roles**: each role is a list of permissions. Sensitive roles (Administrator, HR, Finance) ask for a second step at sign-in.
- **Hiring and departures**: checklists, documents to collect, letters to sign.
- **Departure**: the final settlement (notice, leave, severance) is calculated in Departures; the last payslip carries it.
- **Import data**: Excel templates for employees, contracts, salaries, leave balances and year-to-date totals. Nothing is saved before the check.` },
  },
  {
    id: 'payroll', audience: 'payroll', routes: ['/payroll', '/payslips', '/payroll/corrections', '/payroll/compare'],
    fr: { title: 'La paie du mois, du calcul à la clôture', body: `
1. **Calculer** (RH) : HR Pay prend les pointages, congés, heures supplémentaires approuvées, primes et prêts du mois, au taux BCC du jour.
2. **Vérifier** : les alertes signalent un salaire manquant, un compte de paiement absent, une forte variation. Comparez avec le mois précédent.
3. **Approuver** (Finance).
4. **Clôturer** (RH) : les bulletins sont numérotés et envoyés, les remboursements de prêt enregistrés.
5. **Payer et déclarer** : fichiers de paiement et déclarations CNSS, IPR, INPP, ONEM.

Après clôture, une correction se fait par bulletin complémentaire ou rappel de salaire, jamais en modifiant le bulletin.` },
    en: { title: "The month's payroll, from calculation to closing", body: `
1. **Calculate** (HR): HR Pay takes the month's check-ins, leave, approved overtime, bonuses and loans, at the day's BCC rate.
2. **Check**: warnings point out a missing salary, a missing payment account, a large change. Compare with the previous month.
3. **Approve** (Finance).
4. **Close** (HR): payslips are numbered and sent, loan repayments recorded.
5. **Pay and declare**: payment files and CNSS, IPR, INPP, ONEM declarations.

After closing, a correction is made with a complementary payslip or back pay, never by editing the payslip.` },
  },
  {
    id: 'payments', audience: 'payroll', routes: ['/payroll/payments', '/payroll/declarations', '/payroll/accounting', '/export-layouts'],
    fr: { title: 'Paiements, déclarations et comptabilité', body: `
- **Paiements** : un fichier par banque ou opérateur de mobile money, dans la mise en page choisie (Mises en page des fichiers).
- **Déclarations** : les montants de chaque déclaration du mois, en CDF ; notez la date de dépôt.
- **Comptabilité** : les écritures du mois au plan SYSCOHADA, en fichier pour votre logiciel comptable.` },
    en: { title: 'Payments, declarations and accounting', body: `
- **Payments**: one file per bank or mobile money operator, in the layout chosen (File layouts).
- **Declarations**: the amounts of each of the month's declarations, in CDF; record the filing date.
- **Accounting**: the month's entries in the SYSCOHADA chart, as a file for your accounting software.` },
  },
  {
    id: 'settings', audience: 'admin', routes: ['/payroll/settings', '/payroll/rates', '/payroll/elements', '/schedules', '/holidays', '/sites', '/org/manage', '/overtime'],
    fr: { title: 'Paramètres : grades, taux, horaires, jours fériés, sites', body: `
- **Grades** : le salaire de base par grade ; un salaire négocié peut différer par employé.
- **Taux** : CNSS, IPR, INPP, ONEM et les autres taux légaux, avec leur date d'effet. Les taux fournis sont à vérifier par votre comptable.
- **Horaires** : les heures prévues par jour ; elles servent au calcul du salaire et des retards.
- **Jours fériés** : ceux du pays, avec la date réellement observée quand le gouvernement la déplace.
- **Sites** : zone GPS, adresses IP, et la règle des pointages hors connexion.` },
    en: { title: 'Settings: grades, rates, schedules, holidays, sites', body: `
- **Grades**: the basic salary per grade; a negotiated salary can differ per employee.
- **Rates**: CNSS, IPR, INPP, ONEM and the other statutory rates, with their effective date. The rates provided are to be checked by your accountant.
- **Schedules**: the hours planned per day; they drive the salary and lateness calculations.
- **Holidays**: the country's, with the date actually observed when the government moves it.
- **Sites**: GPS area, IP addresses, and the rule for check-ins made offline.` },
  },
  {
    id: 'sign-in', audience: 'everyone', routes: ['/account'],
    fr: { title: 'Connexion, deuxième étape et mot de passe oublié', body: `
- **Mot de passe oublié** : sur la page de connexion, « Mot de passe oublié ? » envoie un lien valable 30 minutes.
- **Deuxième étape** : un code d'une application d'authentification, ou envoyé par e-mail. Gardez vos codes de secours. Téléphone perdu : un administrateur la réinitialise (Utilisateurs, puis la personne).
- **Navigateurs connectés** : Mon compte les liste ; déconnectez ceux que vous ne reconnaissez pas.` },
    en: { title: 'Signing in, second step and forgotten password', body: `
- **Forgotten password**: on the sign-in page, "Forgot your password?" sends a link valid for 30 minutes.
- **Second step**: a code from an authenticator app, or sent by email. Keep your recovery codes. Lost phone: an administrator resets it (Users, then the person).
- **Signed-in browsers**: My account lists them; sign out the ones you don't recognise.` },
  },
  {
    id: 'dashboards', audience: 'managers', routes: ['/dashboards', '/reports'],
    fr: { title: 'Tableaux de bord et rapports', body: `
**Tableaux de bord** montre, mois par mois : l'effectif en fin de mois, les entrées et sorties, le taux de rotation, l'absence non payée et le coût de la paie par direction (brut et charges patronales, en USD au taux de la paie).

- La rotation = départs du mois ÷ effectif moyen du mois.
- L'absence vient des bulletins : le temps prévu non payé (absences, retards).
- Le coût d'un mois apparaît une fois la paie calculée ; il est définitif après approbation.

**Rapports** exporte les données détaillées en Excel ou CSV.` },
    en: { title: 'Dashboards and reports', body: `
**Dashboards** shows, month by month: headcount at month end, hires and departures, turnover, unpaid absence and payroll cost by direction (gross and employer charges, in USD at the payroll rate).

- Turnover = the month's departures ÷ the month's average headcount.
- Absence comes from payslips: scheduled time not paid (absences, lateness).
- A month's cost shows once payroll is calculated; it is final after approval.

**Reports** exports the detailed data to Excel or CSV.` },
  },
  {
    id: 'support', audience: 'everyone', routes: ['/help', '/support'],
    fr: { title: 'Obtenir de l’aide', body: `
- **Signaler un problème** (menu de votre nom, ou cette page) : décrivez ce qui se passe ; la page ouverte et la version sont jointes automatiquement.
- Vous êtes prévenu de la réponse dans l'application et par e-mail ; répondez ou fermez la demande depuis Aide.
- Un administrateur de l'entreprise voit toutes les demandes de l'entreprise.
- Si l'application ne répond plus, consultez la [page d'état](/status).` },
    en: { title: 'Getting help', body: `
- **Report a problem** (menu under your name, or this page): describe what happens; the page you are on and the version are attached automatically.
- You are told of the answer in the app and by email; reply or close the request from Help.
- A company administrator sees all the company's requests.
- If the app stops answering, look at the [status page](/status).` },
  },
]

export const articleText = (a: Article) => (language() === 'fr' ? a.fr : a.en)

/** The article that explains a page, if any. */
export function articleFor(path: string): Article | undefined {
  let best: { a: Article; len: number } | undefined
  for (const a of ARTICLES) {
    for (const r of a.routes) {
      if ((path === r || path.startsWith(r + '/')) && (!best || r.length > best.len)) best = { a, len: r.length }
    }
  }
  return best?.a
}
