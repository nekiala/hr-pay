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
    id: 'missions', audience: 'everyone', routes: ['/missions', '/mission-check'],
    fr: { title: 'Missions et ordre de mission', body: `
Une **mission** envoie un employé travailler ailleurs pour des jours entiers : à Lubumbashi pour un déploiement, au Japon pour une formation.

1. **Demande** : l'employé la demande dans **Missions** (pays, ville, dates, objet), ou les RH la créent pour une ou plusieurs personnes.
2. **Approbation** : le chef d'unité approuve. Pour une mission à l'étranger, les RH approuvent ensuite (réglable dans les paramètres).
3. **Ordre de mission** : une fois approuvée, la mission reçoit un numéro (OM-2026-0001) et un PDF sur le papier à en-tête de l'entreprise, en français, avec une page en anglais pour l'étranger. Son **code QR** ouvre une page qui confirme, à un agent de l'aéroport ou à l'hôte, que l'ordre est authentique et valide.
4. **Présence et paie** : chaque jour de travail de la mission compte comme travaillé et payé en entier, sans pointage. Le salaire ne change pas. L'indemnité de transport s'arrête ces jours-là si l'entreprise l'a choisi (l'employé ne fait pas le trajet domicile-travail).
5. **Frais** : les RH versent une avance (per diem du barème, et l'hôtel si l'entreprise ne le réserve pas) par un fichier de paiement, hors bulletin. Pendant ou après la mission, l'employé ajoute ses dépenses avec la photo du reçu.
6. **Retour** : l'employé dépose son rapport. Les RH acceptent ou refusent chaque dépense, puis **soldent** la mission : le reste dû est payé, ou l'employé rend le trop-perçu.

Les frais de voyage ne sont pas de la rémunération pour la CNSS (RDC, arrêté 146/2018 art. 17). Un per diem au-dessus du barème de l'entreprise passe sur le bulletin comme salaire imposable, avec CNSS et impôt. Faites confirmer les montants du barème par votre conseiller paie.` },
    en: { title: 'Missions and the mission order', body: `
A **mission** sends an employee to work elsewhere for whole days: to Lubumbashi for a deployment, to Japan for a training session.

1. **Request**: the employee asks in **Missions** (country, city, dates, purpose), or HR creates it for one or more people.
2. **Approval**: the unit head approves. For a mission abroad, HR approves next (a setting).
3. **Ordre de mission**: once approved, the mission gets a number (OM-2026-0001) and a PDF on the company letterhead, in French, with a page in English for missions abroad. Its **QR code** opens a page that tells an airport officer or the host that the order is genuine and valid.
4. **Attendance and pay**: every working day of the mission counts as worked and fully paid, without checking in. The salary does not change. The transport allowance stops on those days if the company chose so (the employee does not commute).
5. **Costs**: HR pays an advance (the per diem from the scale, and the hotel when the company does not book it) through a payment file, off the payslip. During or after the mission, the employee adds each cost with a photo of the receipt.
6. **Return**: the employee files the report. HR accepts or refuses each cost, then **settles** the mission: what is still due is paid, or the employee gives back the difference.

Travel costs are not pay for CNSS (DRC, Arrêté 146/2018 art. 17). A per diem above the company scale goes on the payslip as taxable pay, with CNSS and income tax. Have your payroll adviser confirm the scale amounts.` },
  },
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
    id: 'leave', audience: 'everyone', routes: ['/leave'],
    fr: { title: 'Demander un congé ou une mission', body: `
Dans **Congés**, choisissez le type, les dates (une demi-journée est possible sur un jour) et joignez un document si on vous le demande. Votre solde est affiché avant l'envoi.

- La demande va au responsable de votre unité, ou à son adjoint s'il est absent.
- Vous êtes prévenu de la décision par une notification et par e-mail.
- Une **mission** se demande dans **Missions** ; les jours de mission comptent comme travaillés (voir l'article sur les missions).` },
    en: { title: 'Asking for leave or a mission', body: `
In **Leave**, choose the type and the dates (half a day is possible on a single day) and attach a document when asked. Your balance shows before you send.

- The request goes to your unit's head, or their deputy when they are away.
- You are told of the decision by a notification and by email.
- A **mission** is asked in **Missions**; mission days count as worked (see the article on missions).` },
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
- **Mon dossier** : les documents que les RH gardent pour vous, et ceux que vous leur envoyez.
- **Santé au travail** : vos visites médicales (seul le résultat est montré, jamais un diagnostic) et le bouton pour déclarer un accident du travail ; les RH sont prévenues tout de suite.` },
    en: { title: 'Signing a document and asking for a certificate', body: `
In **My documents**:

- **To sign**: read the PDF, ask for a code (sent by email or WhatsApp), enter it with your full name. The signed copy goes into your file. You can also refuse and give the reason.
- **Certificates**: ask for an employment or salary certificate; you are told when it is ready.
- **My file**: the documents HR keeps for you, and the ones you send them.
- **Health at work**: your medical visits (only the result is shown, never a diagnosis) and the button to report a work accident; HR is told at once.` },
  },
  {
    id: 'jobs', audience: 'everyone', routes: ['/jobs'],
    fr: { title: 'Les tâches longues en arrière-plan', body: `
Calculer ou clôturer la paie de milliers de personnes, importer un fichier Excel ou préparer tous les bulletins en zip prend du temps. HR Pay le fait **en arrière-plan** :

- une barre de progression s'affiche en bas de l'écran ; vous pouvez quitter la page et continuer à travailler ;
- la **cloche** vous prévient quand c'est terminé (et un e-mail si cela a pris plus de 2 minutes) ;
- **Mes tâches** liste vos tâches des 30 derniers jours : état, durée, fichier à télécharger (gardé 7 jours) ;
- une tâche en attente peut être annulée, une tâche en échec affiche la raison et peut être relancée.

Une même paie ne peut pas être calculée deux fois en même temps : relancer le calcul montre celui en cours. Si un serveur s'arrête pendant une tâche, elle reprend d'elle-même.` },
    en: { title: 'Long tasks in the background', body: `
Calculating or closing the payroll of thousands of people, importing an Excel file or preparing every payslip as a zip takes time. HR Pay does it **in the background**:

- a progress bar shows at the bottom of the screen; you can leave the page and keep working;
- the **bell** tells you when it ends (and an email if it took more than 2 minutes);
- **My jobs** lists your jobs of the last 30 days: status, time taken, file to download (kept 7 days);
- a waiting job can be cancelled; a failed job shows why and can be started again.

The same payroll cannot be calculated twice at once: starting it again shows the one running. If a server stops during a job, the job starts again by itself.` },
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
    id: 'people', audience: 'hr', routes: ['/users', '/roles', '/departures', '/imports'],
    fr: { title: 'RH : employés, rôles, embauches et départs', body: `
- **Utilisateurs** : créez la fiche, l'unité, le contrat et le salaire. Un mot de passe provisoire est changé à la première connexion.
- **Rôles** : chaque rôle est une liste de permissions. Les rôles sensibles (Administrateur, RH, Finance) demandent une deuxième étape à la connexion.
- **Embauches et départs** : les listes de contrôle, les documents à réunir, les lettres à signer.
- **Départ** : le décompte final (préavis, congés, indemnités) se calcule dans Départs ; le dernier bulletin le reprend.
- **Promouvoir ou muter** (fiche contrat et famille) : un nouveau grade ou une nouvelle fonction fait un avenant ; une nouvelle unité ou un nouveau site fait une mutation. HR Pay rédige l'avenant ou la note de mutation à faire signer : le poste et le lieu de travail font partie du contrat écrit (Code du travail, art. 36, 44, 55 et 61 ter). Une période d'essai éventuelle est écrite dans l'avenant, 6 mois au plus (art. 43). Une mutation hors du lieu d'emploi oblige à loger le travailleur et sa famille ou à verser une indemnité (art. 138). L'ancienneté ne change pas.
- **Importer des données** : modèles Excel pour les employés, la famille, les contrats, les mouvements (promotions et mutations), les salaires, les soldes de congés et les cumuls de l'année. Les employés, la famille et les cumuls se téléchargent aussi remplis avec les données actuelles : modifiez le fichier et importez-le. Rien n'est enregistré avant la vérification.` },
    en: { title: 'HR: employees, roles, hiring and departures', body: `
- **Users**: create the person, their unit, contract and salary. A temporary password is changed at the first sign-in.
- **Roles**: each role is a list of permissions. Sensitive roles (Administrator, HR, Finance) ask for a second step at sign-in.
- **Hiring and departures**: checklists, documents to collect, letters to sign.
- **Departure**: the final settlement (notice, leave, severance) is calculated in Departures; the last payslip carries it.
- **Promote or transfer** (contract and family page): a new grade or occupation makes an avenant; a new unit or site makes a transfer. HR Pay writes the avenant or the note de mutation for the employee to sign: the post and the place of work belong to the written contract (Labour Code art. 36, 44, 55 and 61 ter). A trial period, if any, is written in the avenant, 6 months at most (art. 43). A transfer outside the place of employment owes housing for the worker and the family, or an allowance (art. 138). Seniority does not change.
- **Import data**: Excel templates for employees, family, contracts, moves (promotions and transfers), salaries, leave balances and year-to-date totals. Employees, family and year-to-date totals also download filled with the current data: change the file and import it back. Nothing is saved before the check.` },
  },
  {
    id: 'payroll', audience: 'payroll', routes: ['/payroll', '/payslips', '/payroll/runs/monthly', '/payroll/runs/corrections', '/payroll/new', '/payroll/compare'],
    fr: { title: 'Les paies, du calcul à la clôture', body: `
Toutes les paies se lancent depuis **Paies** > **Nouvelle paie** : choisissez le type (paie de fin de mois, quinzaine, allocation de congé, 13e mois, corrections, décompte final), remplissez ses conditions, puis lancez-la. La page Paies montre les paies du mois par date de paiement et ce qui les bloque (quinzaine ouverte, 13e mois non approuvé, allocation de congé en retard).

La quinzaine et l'allocation de congé versent de l'argent avant la fin du mois ; la CNSS et l'IPR se calculent sur le bulletin de fin de mois, qui les reprend. Le 13e mois approuvé est payé par la paie du mois choisi.

1. **Calculer** (RH) : HR Pay prend les pointages, congés, heures supplémentaires approuvées, primes et prêts du mois, au taux BCC du jour.
2. **Vérifier** : les alertes signalent un salaire manquant, un compte de paiement absent, une forte variation. Comparez avec le mois précédent.
3. **Approuver** (Finance).
4. **Clôturer** (RH) : les bulletins sont numérotés et envoyés, les remboursements de prêt enregistrés.
5. **Payer et déclarer** : fichiers de paiement et déclarations CNSS, IPR, INPP, ONEM.

Après clôture, une correction se fait par bulletin complémentaire ou rappel de salaire, jamais en modifiant le bulletin.` },
    en: { title: 'Pay runs, from calculation to closing', body: `
Every pay run starts from **Pay runs** > **New pay run**: pick the type (month-end payroll, quinzaine, leave pay, 13th month, corrections, final settlement), fill in its conditions, then run it. The Pay runs page shows the month's runs by pay date and what holds them up (an open quinzaine, a 13th month not approved, leave pay paid late).

The quinzaine and leave pay pay money before the month ends; CNSS and income tax are worked out on the month-end payslip, which takes them back. An approved 13th month is paid by the payroll of the month chosen.

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
- **Comptabilité** : les écritures du mois au plan SYSCOHADA, en fichier pour votre logiciel comptable. Un compte laissé vide prend le compte SYSCOHADA par défaut ; la paie des expatriés va en 662x et leur CNSS en 6642.` },
    en: { title: 'Payments, declarations and accounting', body: `
- **Payments**: one file per bank or mobile money operator, in the layout chosen (File layouts).
- **Declarations**: the amounts of each of the month's declarations, in CDF; record the filing date.
- **Accounting**: the month's entries in the SYSCOHADA chart, as a file for your accounting software. An empty account takes the default SYSCOHADA one; expatriates' pay goes to 662x and their CNSS to 6642.` },
  },
  {
    id: 'settings', audience: 'admin', routes: ['/payroll/settings', '/payroll/rates', '/payroll/elements', '/schedules', '/holidays', '/sites', '/org/manage', '/overtime'],
    fr: { title: 'Paramètres : grades, taux, horaires, jours fériés, sites', body: `
- **Grades** : le salaire de base par grade ; un salaire négocié peut différer par employé.
- **Taux** : CNSS, IPR, INPP, ONEM et les autres taux légaux, avec leur date d'effet. Les taux fournis sont à vérifier par votre comptable.
- **Horaires** : les heures prévues par jour ; elles servent au calcul du salaire et des retards.
- **Jours fériés** : ceux du pays, avec la date réellement observée quand le gouvernement la déplace.
- **Sites** : zone GPS, adresses IP, et la règle des pointages hors connexion.
- **Augmentation d'ancienneté** : à chaque anniversaire du contrat d'un permanent, le salaire de base augmente du pourcentage de sa catégorie appliqué au salaire de base du grade. Elle s'applique seule chaque matin ; les RH reçoivent le 1er du mois un e-mail qui liste les augmentations.
- **Préavis** : une base plus des jours par année de service complète ; les jours ouvrables excluent les dimanches et jours fériés. En RDC : catégories 1 à 5, 14 jours ouvrables + 7 par année (Code du travail art. 64) ; agents de maîtrise, 1 mois + 9 jours ouvrables par année ; cadres, 3 mois + 16 jours ouvrables par année (arrêté du 26 octobre 2005) ; l'employé qui démissionne en doit la moitié. Une convention collective ne peut que donner plus.
- **Éléments de paie** : les traitements CNSS et IPR fournis sont des exemples, à faire confirmer avant la mise en service. Les éléments « calculés par la paie » sont intégrés : on peut seulement les renommer.` },
    en: { title: 'Settings: grades, rates, schedules, holidays, sites', body: `
- **Grades**: the basic salary per grade; a negotiated salary can differ per employee.
- **Rates**: CNSS, IPR, INPP, ONEM and the other statutory rates, with their effective date. The rates provided are to be checked by your accountant.
- **Schedules**: the hours planned per day; they drive the salary and lateness calculations.
- **Holidays**: the country's, with the date actually observed when the government moves it.
- **Sites**: GPS area, IP addresses, and the rule for check-ins made offline.
- **Anniversary raise**: on a permanent employee's contract anniversary, the basic salary rises by their category's percentage of the grade's base salary. It is applied by itself each morning; HR gets an email on the 1st of the month listing the raises.
- **Notice**: a base plus days for each full year of service; working days leave out Sundays and public holidays. DRC: categories 1 to 5, 14 working days + 7 per year (Labour Code art. 64); agents de maîtrise, 1 month + 9 working days per year; cadres, 3 months + 16 working days per year (arrêté of 26 October 2005); an employee who resigns owes half. A collective agreement can only give more.
- **Pay elements**: the CNSS and IPR treatments provided are samples, to be confirmed before go-live. Elements "calculated by payroll" are built in: you can only rename them.` },
  },
  {
    id: 'sign-in', audience: 'everyone', routes: ['/account'],
    fr: { title: 'Connexion, deuxième étape et mot de passe oublié', body: `
- **Mot de passe oublié** : sur la page de connexion, « Mot de passe oublié ? » envoie un lien valable 30 minutes.
- **Deuxième étape** : un code d'une application d'authentification, ou envoyé par e-mail. Gardez vos codes de secours. Téléphone perdu : un administrateur la réinitialise (Utilisateurs, puis la personne).
- **Navigateurs connectés** : Mon compte les liste ; déconnectez ceux que vous ne reconnaissez pas.
- **Connexion avec Google** : possible avec le compte Google professionnel de votre entreprise (Google Workspace), si l'administrateur a enregistré son domaine sur la page Rôles. Les comptes Gmail personnels sont refusés. Votre e-mail dans HR Pay doit être celui de ce compte Google. La deuxième étape est toujours demandée.
- **Pour l'administrateur** : page Rôles, carte « Se connecter avec Google », listez les domaines de l'entreprise (par exemple monentreprise.com). « Google uniquement » refuse le mot de passe des personnes sur ces domaines : leur accès s'arrête dès que leur compte Google est fermé.` },
    en: { title: 'Signing in, second step and forgotten password', body: `
- **Forgotten password**: on the sign-in page, "Forgot your password?" sends a link valid for 30 minutes.
- **Second step**: a code from an authenticator app, or sent by email. Keep your recovery codes. Lost phone: an administrator resets it (Users, then the person).
- **Signed-in browsers**: My account lists them; sign out the ones you don't recognise.
- **Sign in with Google**: possible with your company's professional Google account (Google Workspace), once the administrator has registered its domain on the Roles page. Personal Gmail accounts are refused. Your email in HR Pay must be that Google account. The second step is still asked.
- **For the administrator**: Roles page, "Sign in with Google" card, list the company's domains (for example mycompany.com). "Google only" refuses the password of people on those domains: their access stops as soon as their Google account is closed.` },
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
    id: 'quinzaine', audience: 'payroll', routes: ['/payroll/runs/quinzaine', '/payroll/quinzaine/settings'],
    fr: { title: 'La quinzaine (acompte de mi-mois)', body: `
Une avance sur la paie du mois, versée vers le milieu du mois. Elle se calcule comme la paie de fin de mois, se paie par les fichiers de paiement, et le bulletin du mois la reprend en totalité sur le net.

- **Réglages** (bouton Paramètres) : l'entreprise l'active, choisit la base (net d'un mois normal ou salaire de base), le jour de paie et la limite de dette ; un taux de 10 à 50 % par catégorie de grade. Une catégorie sans taux ne la reçoit pas.
- **Jour de paie** : un week-end ou un jour férié, elle est payée le jour ouvrable précédent.
- **Qui la reçoit** : toute personne sous contrat le jour de paie, sauf celles qui ont demandé à ne pas la recevoir (depuis leur page de bulletins, à partir de la prochaine quinzaine pas encore approuvée), celles que vous écartez, un congé sans solde le jour de paie, un départ dans le mois et une dette au-dessus de la limite.
- **Montant** : jamais plus que le net déjà acquis le jour de paie, pour que le bulletin du mois puisse toujours la reprendre. Tant que la Finance ne l'a pas approuvée, un nouveau calcul la remplace.
- **Étapes** : les RH calculent, la Finance approuve, les RH clôturent (les reçus sont numérotés), puis Paiements.

**Le droit.** RDC (Code du travail art. 99) : payer au moins une fois par mois, au plus tard six jours après ; les avances peuvent être reprises sur la paie (art. 112). République du Congo (art. 88 et 89) : payer au plus tard huit jours après le mois ; quand des travailleurs à la journée ou à l'heure sont payés au mois, l'acompte de chaque quinzaine vaut au moins un tiers de la paie du mois précédent. À confirmer avec votre expert paie.` },
    en: { title: 'The quinzaine (mid-month advance)', body: `
An advance on the month's pay, paid around the middle of the month. It is worked out like the month-end payroll, paid through the payment files, and the month's payslip takes it back in full from the net pay.

- **Settings** (Settings button): the company turns it on and chooses the base (net pay of a normal month, or basic salary), the pay day and the debt limit; a rate of 10 to 50% per grade category. A category without a rate does not get it.
- **Pay day**: on a weekend or public holiday, it is paid the working day before.
- **Who gets it**: everyone under contract on the pay day, except those who asked not to (from their payslips page, from the next quinzaine not yet approved), those you leave out, unpaid leave on the pay day, a departure in the month, and debts above the limit.
- **Amount**: never more than the net already earned on the pay day, so the month's payslip can always take it back. Until Finance approves it, calculating again replaces it.
- **Steps**: HR calculates, Finance approves, HR closes (receipts get their numbers), then Payments.

**The law.** DRC (Labour Code art. 99): pay at least once a month, within six days after it; advances may be taken back on the pay (art. 112). Republic of the Congo (art. 88 and 89): pay within eight days after the month; where day or hour workers are paid monthly, the acompte each fortnight must be at least a third of the previous month's pay. To confirm with your payroll expert.` },
  },
  {
    id: 'payroll-reports', audience: 'payroll', routes: ['/payroll/reports'],
    fr: { title: 'Les états généraux de la paie', body: `
Les états d'un mois de paie, à l'écran, en PDF ou en Excel. Tous les montants viennent des bulletins enregistrés : un état est toujours égal à ce qui a été payé.

- **Récapitulatif général** : effectif, brut, bases CNSS et imposable, retenues, net, charges patronales et coût total, puis le détail par élément de paie.
- **Totaux par regroupement** : par catégorie, grade, site, unité (au niveau choisi : direction, département, service...), centre de coût, fonction, type de contrat, nationaux et expatriés, devise ou mode de paiement, avec un second regroupement et des sous-totaux.
- **Structure** : chaque unité avec le total de toute sa branche. **Par élément de paie** et **éléments par regroupement** (tableau croisé).
- **Mode de paiement** : banque par banque, mobile money par opérateur, espèces ; les numéros de compte restent masqués.
- **Écarts avec le mois précédent** : arrivées, départs, et nets qui changent de plus de 10 % (réglable) avec les éléments qui ont le plus bougé.
- **Quinzaine** : montants par catégorie, site, unité et mode de paiement, puis ce que le bulletin du mois a repris.
- **Livre de paie** : une ligne par bulletin avec les 33 mentions du modèle officiel, totaux par page et report, en monnaie locale. Seulement pour un mois clôturé.

Cliquez sur une ligne pour voir les employés concernés (droit de lire tous les bulletins). Avant la clôture, les états portent la mention PROVISOIRE. Chaque téléchargement est inscrit au journal d'audit.

**Le droit.** RDC : le Code du travail (art. 213) impose un livre de paie ; l'arrêté ministériel 12/CAB.MIN/ETPS/042 du 8 août 2008 en fixe le modèle (33 mentions) pour 10 travailleurs ou plus, sur papier ou fichier informatique, sans modification après inscription. Aucune durée de conservation n'a été trouvée : HR Pay ne supprime jamais une paie clôturée. République du Congo : à confirmer avec votre expert paie. La branche d'activité imprimée en tête vient de la page Entreprise.` },
    en: { title: 'Payroll general reports', body: `
The statements of a payroll month, on screen, in PDF or in Excel. Every amount comes from the stored payslips, so a report always equals what was paid.

- **General summary**: headcount, gross, CNSS and taxable bases, deductions, net, employer charges and total cost, then the detail by pay element.
- **Totals by grouping**: by grade category, grade, site, unit (at the level you choose: direction, department, service...), cost centre, occupation, contract type, nationals and expatriates, currency or payment method, with a second grouping and subtotals.
- **Organisational structure**: each unit with the total of its whole branch. **By pay element** and **pay elements by grouping** (a cross table).
- **Payment method**: bank by bank, mobile money by operator, cash; account numbers stay masked.
- **Differences with last month**: arrivals, departures, and nets that change by more than 10% (adjustable) with the elements that moved most.
- **Quinzaine**: amounts by category, site, unit and payment method, then what the month's payslip took back.
- **Livre de paie**: one line per payslip with the 33 mentions of the official model, page totals carried forward, in the local currency. Closed months only.

Click a row to see the employees behind it (needs the right to read all payslips). Before closing, reports are marked PROVISIONAL. Every download is written to the audit log.

**The law.** DRC: the Labour Code (art. 213) requires a livre de paie; ministerial order 12/CAB.MIN/ETPS/042 of 8 August 2008 sets its model (33 mentions) for 10 workers or more, on paper or as a computer file, never changed once written. No retention period was found: HR Pay never deletes a closed payroll. Republic of the Congo: to confirm with your payroll expert. The branch of activity printed at the top comes from the Company page.` },
  },
  {
    id: 'pay-simulation', audience: 'payroll', routes: ['/payroll/simulate'],
    fr: { title: 'Simuler la paie et projeter l\'année', body: `
**Simuler la paie** calcule la paie d'une personne avec le vrai moteur de paie, sans rien enregistrer : ce n'est pas un bulletin (Code du travail art. 103), il n'a pas de numéro, n'entre pas dans le livre de paie et l'employé ne le voit pas. Seul le journal d'audit garde qui a regardé la paie de qui.

- **Type de paie** : bulletin de fin de mois (n'importe quel mois), quinzaine, allocation de congé (dates du congé), 13e mois (année) ou décompte final (dernier jour et motif).
- **Et si** : changez le salaire de base, le complément, une prime ponctuelle, des heures supplémentaires, des jours non payés, les enfants ou le taux du dollar pour voir l'effet. Un champ vide garde ce qui est enregistré.
- Pour un mois déjà payé, le bulletin payé est affiché à côté pour comparer. Le PDF porte la mention SIMULATION.

**Projection annuelle** : mois par mois jusqu'en décembre. Les mois clôturés sont repris tels que payés ; les autres sont calculés avec ce qui est connu aujourd'hui : changements de salaire enregistrés, augmentations d'ancienneté à venir, congés annuels approuvés ou demandés avec leur allocation (payée avant le départ, art. 145), 13e mois, prêts, fin de CDD, enfants qui atteignent la limite d'âge, taux saisis pour des dates futures. Les primes et heures supplémentaires à venir sont la moyenne des 12 derniers mois, ou rien.

Le congé non pris au 31 décembre n'est pas payé (art. 144) : la projection montre combien de jours resteront et ce qu'ils valent, payés seulement à la fin du contrat.

**Projection de l'entreprise** : la même chose pour tout le monde, ou pour une unité et tout ce qui est en dessous, additionnée par mois. Une grande entreprise la calcule en arrière-plan (Mes tâches). Export Excel.

**Augmentation générale** : une révision du barème. Choisissez un pourcentage (avec un montant minimum et un arrondi si besoin), le mois de départ et les catégories ou grades (aucun coché : tout le barème). Les bases des grades augmentent, et chaque personne de ces grades reçoit le même montant sur son salaire de base (art. 86 : à travail égal, salaire égal) ; le complément de salaire ne change pas. Le résultat montre, par devise, le coût mensuel en plus (brut, net, charges patronales), le coût jusqu'au 31 décembre et sur 12 mois, les nouvelles bases face au minimum légal (art. 87 et 88) et chaque personne avant et après. Rien n'est enregistré.
- Enregistrez plusieurs versions (par exemple 3 % et 5 %) et cochez-les pour les comparer côte à côte.
- **Envoyer pour approbation** : la Finance ou un administrateur approuve, jamais la personne qui l'a préparée. L'approbation enregistre les salaires à partir du mois choisi ; les bases des grades changent ce mois-là.
- **Mois déjà approuvés** : un départ dans le passé (12 mois au plus) donne un **rappel de salaire** sur la prochaine paie, soumis à la CNSS et à l'impôt.
- **Annuler** : possible tant qu'aucune paie à partir de ce mois (ou du mois du rappel) n'est approuvée.

La permission « Simuler et projeter la paie » est donnée aux RH ; la page Rôles peut la donner à d'autres. Personne ne peut simuler sa propre paie. « Préparer une augmentation générale » est donnée aux RH, « Approuver une augmentation générale » à la Finance et aux administrateurs.` },
    en: { title: 'Simulating pay and projecting the year', body: `
**Simulate pay** works out one person's pay with the real payroll engine and saves nothing: it is not a payslip (Labour Code art. 103), has no number, is not in the livre de paie and the employee never sees it. Only the audit log keeps who looked at whose pay.

- **Pay type**: month-end payslip (any month), quinzaine, allocation de congé (the leave's dates), 13th month (a year) or final settlement (last day and reason).
- **What if**: change the basic salary, the complement, a one-off bonus, overtime hours, days not paid, children or the dollar rate to see the effect. An empty field keeps what is recorded.
- For a month already paid, the payslip paid is shown beside it. The PDF is marked SIMULATION.

**Year projection**: month by month to December. Closed months are shown as paid; the others are worked out with what is known today: salary changes recorded, anniversary raises to come, approved or requested annual leave with its allocation (paid before the leave, art. 145), the 13th month, loans, the end of a fixed-term contract, children reaching the age limit, rates entered for later dates. Bonuses and overtime to come are the last 12 months' average, or none.

Leave not taken by 31 December is not paid (art. 144): the projection shows how many days will be left and what they are worth, paid only when the contract ends.

**Company projection**: the same for everyone, or for a unit and everything below it, added up by month. A large company runs it in the background (My jobs). Excel export.

**General increase**: a revision of the grade scale. Choose a percentage (with a minimum amount and rounding if needed), the start month and the categories or grades (none ticked: the whole scale). The grade bases go up, and everyone on those grades gets the same amount on their basic salary (art. 86: equal pay for equal work); the salary complement does not change. The result shows, by currency, the extra monthly cost (gross, net, employer charges), the cost to 31 December and over 12 months, the new bases against the legal minimum (art. 87 and 88) and each person before and after. Nothing is saved.
- Save several versions (say 3% and 5%) and tick them to compare side by side.
- **Send for approval**: Finance or an administrator approves, never the person who prepared it. Approval records the salaries from the chosen month; the grade bases change on that month.
- **Months already approved**: a start in the past (12 months at most) gives **back pay** (Rappel de salaire) on the next payroll, subject to CNSS and income tax.
- **Undo**: possible while no payroll from that month (or the back pay month) is approved.

HR has the "Simulate and project pay" permission; the Roles page can give it to others. Nobody can simulate their own pay. HR prepares general increases; Finance and administrators approve them.` },
  },
  {
    id: 'annual-leave', audience: 'hr', routes: ['/payroll/runs/leave-pay', '/leave/settings'],
    fr: { title: 'Congé annuel : acquisition, report et allocation', body: `
- **Acquisition** : chaque paie mensuelle clôturée crédite les jours de la catégorie de la personne ÷ 12, jamais sous le minimum légal (RDC art. 141 : 1 jour ouvrable par mois complet, 1,5 avant 18 ans), plus les jours d'ancienneté, au prorata du mois en service. Le congé sans solde et l'absence injustifiée ne comptent pas. Le bulletin montre les jours acquis et le solde.
- **Ancienneté** : comptée depuis la date d'embauche ; mettez 0 an pour n'en donner aucun.
- **Report** : RDC (art. 140) : seule la moitié du congé peut être reportée, sur deux ans (50 % pendant 1 an). République du Congo (art. 120) : le droit se perd après 3 ans (100 % pendant 3 ans).
- **Types de congé** : 100 % garde le salaire du jour, 0 % rend le congé non payé. Un type qui exige un justificatif est approuvé par le responsable, puis ne compte qu'une fois le document vérifié par les RH.
- **Allocation de congé** : la rémunération des jours de congé (base et complément de salaire ; le logement, le transport et les allocations familiales ne sont pas de la rémunération, Code du travail art. 7) plus la moyenne des primes, commissions et heures supplémentaires des 12 mois précédents. Elle est payée au plus tard le dernier jour ouvrable avant le congé (art. 142 et 145), avec son propre bulletin. Le bulletin de chaque mois couvert l'ajoute comme gain, pour calculer la CNSS et l'impôt sur le mois entier, et reprend le net payé d'avance.` },
    en: { title: 'Annual leave: earning, carry-over and allowance', body: `
- **Earning**: each closed monthly payroll credits the days of the person's category ÷ 12, never below the legal floor (DRC art. 141: 1 working day per full month, 1.5 under 18), plus the seniority days, for the share of the month in service. Unpaid leave and unjustified absence do not count. The payslip shows the days earned and the balance.
- **Seniority**: counted from the hire date; set the years to 0 to give none.
- **Carry-over**: DRC (art. 140): only half of the leave can be carried, over two years (50% for 1 year). Republic of the Congo (art. 120): the right lapses after 3 years (100% for 3 years).
- **Kinds of leave**: 100% keeps the day's pay, 0% makes the leave unpaid. A kind that needs a document is approved by the manager, then counts only once HR has checked the document.
- **Leave allowance**: the remuneration for the leave days (basic and salary complement; housing, transport and family allowances are not remuneration, Labour Code art. 7) plus the average of bonuses, commissions and overtime of the 12 months before. It is paid by the last working day before the leave (art. 142 and 145), with its own payslip. The payslip of each month the leave covers adds it as an earning, so CNSS and income tax are worked out on the whole month, and takes back the net paid in advance.` },
  },
  {
    id: 'evaluation-setup', audience: 'hr', routes: ['/evaluations/settings'],
    fr: { title: "Critères et modèles d'évaluation", body: `
- **Modèle** : la fréquence des évaluations et le poids de chaque critère ; les poids totalisent 100. Un seul modèle actif par catégorie de grade et unité.
- **Lequel s'applique** : le plus précis, d'abord l'unité de la personne (ou l'unité la plus proche au-dessus qui a un modèle), puis sa catégorie de grade, puis « Tout le monde ».
- **Critères** : le responsable note chacun de 0 à 100. Un critère « d'après les présences » est noté par l'application : 100, moins 5 par jour de retard et 10 par jour d'absence sur la période.` },
    en: { title: 'Evaluation criteria and templates', body: `
- **Template**: how often people are evaluated and how much each criterion weighs; weights add up to 100. One active template per grade category and unit.
- **Which one applies**: the most specific, first the person's unit (or the nearest unit above with a template), then their grade category, then "Everyone".
- **Criteria**: the manager scores each from 0 to 100. A criterion "from attendance" is scored by the app: 100, minus 5 per late day and 10 per absent day in the period.` },
  },
  {
    id: 'loans-entries', audience: 'payroll', routes: ['/payroll/loans', '/payroll/entries'],
    fr: { title: 'Prêts, avances, primes et retenues du mois', body: `
- **Primes et retenues** : des lignes ponctuelles pour un seul mois, comme une prime de performance. Un élément permanent, comme un montant de transport différent, se règle dans le dossier de l'employé (Contrat et famille). Le catalogue des éléments est dans Paramètres › Éléments de paie.
- **Prêts et avances** : chaque mois, les échéances prennent au plus la part du net fixée dans Taux ; le reste attend le mois suivant.` },
    en: { title: "Loans, advances, and the month's bonuses and deductions", body: `
- **Bonuses and deductions**: one-off lines for one month only, such as a performance bonus. A standing item, such as a different transport amount, is set in the employee's file (Contract and family). The catalogue of elements is under Settings › Pay elements.
- **Loans and advances**: each month the installments take at most the share of net pay set under Rates; the rest waits for the next month.` },
  },
  {
    id: 'hiring', audience: 'hr', routes: ['/lifecycle'],
    fr: { title: 'Embauches et départs', body: `
- **À faire** : les listes de contrôle ouvertes, les certificats demandés, les lettres en attente de signature, les documents qui expirent et le matériel encore chez les partants.
- Une liste de départ démarre seule quand un départ est confirmé ; une liste d'embauche se démarre depuis la page de la personne.
- **Réglages** (bouton Paramètres) : les types de documents gardés pour chaque personne, les étapes de chaque liste, et les modèles de contrats et de lettres signés par code. Les textes de départ sont des brouillons : faites-les relire par votre avocat.
- **Essais et fins de contrat** : les essais qui finissent bientôt (rappels 30, 15 et 5 jours avant) et les CDD (60, 30 et 15 jours avant). Pour un essai : Confirmer ou Mettre fin à l'essai. Pour un CDD : Renouveler, Transformer en CDI ou Laisser prendre fin. Un essai passé sans décision est confirmé par la loi (RDC art. 43). Un CDD dont la date est passée alors que la personne travaille encore devient un contrat à durée indéterminée.
- **Journaliers** : un journalier embauché à nouveau après 22 jours de travail en 2 mois doit recevoir un contrat permanent (RDC art. 40) ; l'application le refuse avant.` },
    en: { title: 'Hiring and departures', body: `
- **To do**: open checklists, certificates asked for, letters waiting for a signature, documents about to expire and equipment still out with people leaving.
- A departure checklist starts by itself when a departure is confirmed; start a hiring checklist from the person's page.
- **Settings** (Settings button): the document types kept for each person, the steps of each checklist, and the contract and letter templates signed with a code. The starting texts are drafts: have them checked by your lawyer.
- **Trials and contract ends**: trials ending soon (reminders 30, 15 and 5 days before) and fixed-term contracts (60, 30 and 15 days before). For a trial: Confirm or End the trial. For a fixed-term contract: Renew, Make permanent or Let it end. A trial passed with no decision is confirmed by law (DRC art. 43). A fixed-term contract whose end date has passed while the person still works becomes permanent.
- **Day workers**: a day worker hired again after 22 days of work in 2 months must get a permanent contract (DRC art. 40); the app refuses it before.` },
  },
  {
    id: 'staff-representation', audience: 'hr', routes: ['/representation'],
    fr: { title: 'Délégués du personnel', body: `
- **Délégués** : titulaires et suppléants, élus le, fin du mandat (3 ans en RDC). La page montre combien de délégués l'effectif demande (RDC : dès 10 travailleurs, arrêté 048/2015 ; Congo : dès 7, nombre à saisir).
- **Élections, réunions, règlement** : notez l'annonce de l'élection (au moins 3 semaines avant), les réunions avec l'employeur (au moins tous les 3 mois, procès-verbal à l'inspecteur dans les 8 jours) et la date d'envoi du règlement d'entreprise.
- **Protection** : licencier ou muter un délégué ou un suppléant demande l'autorisation de l'inspecteur du travail (arrêté 036/95). Tant qu'elle n'est pas enregistrée ici, la sanction, le départ ou la mutation est bloqué.
- **Heures de délégation** : entre 15 et 35 heures payées par mois ; la page signale les écarts.` },
    en: { title: 'Staff delegates', body: `
- **Delegates**: titular and alternate delegates, elected on, term end (3 years in the DRC). The page shows how many delegates the headcount requires (DRC: from 10 workers, arrêté 048/2015; Congo: from 7, number to enter).
- **Elections, meetings, rules**: record the election notice (at least 3 weeks ahead), the meetings with the employer (at least every 3 months, minutes to the inspector within 8 days) and when the company rules were sent.
- **Protection**: dismissing or transferring a delegate or an alternate needs the labour inspector's authorisation (arrêté 036/95). Until it is recorded here, the sanction, departure or transfer is blocked.
- **Delegation hours**: 15 to 35 paid hours a month; the page flags anything outside.` },
  },
  {
    id: 'health-safety', audience: 'hr', routes: ['/health'],
    fr: { title: 'Santé et sécurité', body: `
**Visites médicales**
- La liste des visites dues : visite d'embauche, visite annuelle (certificat valable 1 an, 6 mois avant 18 ans) et visite de reprise, planifiée seule après 30 jours d'arrêt ou plus (maladie, accident, maternité).
- Planifiez une visite (la personne est prévenue), puis enregistrez le résultat : apte, apte avec restrictions, inapte pour l'instant ou inapte. Ne notez jamais de diagnostic : le secret médical l'interdit. Le chef d'unité voit seulement le résultat et les restrictions, dans Présence.
- « Liste pour la clinique (PDF) » imprime les visites à venir.

**Accidents du travail**
- Un employé ou un chef d'unité peut déclarer un accident ; les RH complètent les faits et les jours d'arrêt.
- Les jours d'arrêt deviennent un congé d'accident du travail, payé aux deux tiers pendant six mois au plus en RDC (art. 106).
- La déclaration à la CNSS est due dans les 60 jours (120 pour une maladie professionnelle) ; l'échéance est rappelée et figure au calendrier des déclarations. « Préparer la déclaration » crée la lettre sur la page de la personne.

**Comité de sécurité (CSHE)**
- Obligatoire dès 20 travailleurs en RDC (arrêté 043/2008) : membres pour 2 ans, réunion au moins tous les 2 mois, inspection des lieux chaque mois, actions à suivre, rapport annuel avant le 31 mars (PDF ou Excel).
- Les valeurs du Congo sont à vérifier avec votre expert-comptable.` },
    en: { title: 'Health and safety', body: `
**Medical visits**
- The list of visits due: hiring visit, yearly visit (certificate valid 1 year, 6 months under 18) and return-to-work visit, planned by itself after 30 days or more off (sickness, accident, maternity).
- Plan a visit (the person is told), then record the result: fit, fit with restrictions, unfit for now or unfit. Never write a diagnosis: medical secrecy forbids it. Unit heads see only the result and restrictions, on Presence.
- "List for the clinic (PDF)" prints the visits coming up.

**Work accidents**
- An employee or a unit head can report an accident; HR completes the facts and the days off.
- The days off become work accident leave, paid at two thirds for up to six months in the DRC (art. 106).
- The CNSS declaration is due within 60 days (120 for an occupational disease); the deadline is reminded and shown on the declarations calendar. "Draft the declaration" creates the letter on the person's page.

**Safety committee (CSHE)**
- Required from 20 workers in the DRC (arrêté 043/2008): members for 2 years, a meeting at least every 2 months, a monthly workplace inspection, actions to follow, a yearly report before 31 March (PDF or Excel).
- The Congo values are to be checked with your accountant.` },
  },
  {
    id: 'jobs-positions', audience: 'hr', routes: ['/positions'],
    fr: { title: 'Emplois et postes', body: `
**Fiches de poste**
- Chaque emploi a sa fiche : mission, principales tâches (une par ligne), formation, expérience, compétences et conditions de travail, avec sa famille, son rattachement et les grades dans lesquels il est payé.
- Les grades donnent la catégorie légale (exécution, maîtrise, cadre). Un contrat dont le grade sort de la plage reçoit un avertissement.
- Un emploi de manœuvre ne peut avoir qu'un essai d'un mois au plus (Code du travail RDC art. 43) : un essai plus long est refusé.
- Chaque enregistrement garde une version. Un contrat garde la version en vigueur à sa signature ; la fiche PDF de la personne et les champs « fiche de poste » des modèles de contrat utilisent cette version.
- L'employé retrouve sa fiche dans Mes documents.

**Échelles de carrière**
- Pour un emploi, les emplois suivants possibles, avec les années minimum dans l'emploi et la moyenne minimum des évaluations approuvées des deux dernières années. Une formation ou un diplôme requis se vérifie à la main.
- La fiche de la personne montre les étapes suivantes et les conditions remplies ; l'onglet « Promouvables » liste qui les remplit toutes.
- L'échelle ne promeut personne : la promotion passe par Carrière sur la fiche de la personne, avec son avenant.

**Postes approuvés**
- Par année, le nombre de postes de chaque emploi dans chaque unité, avec un budget facultatif, face aux personnes en place et à ce qui a été payé.
- Partez de l'année précédente ou des personnes en place, puis ajustez.
- Engager ou muter quelqu'un dans une unité sans poste libre donne un avertissement ; les RH peuvent continuer. Sans postes saisis pour l'année, rien n'est vérifié.` },
    en: { title: 'Jobs and positions', body: `
**Job descriptions**
- Each job has its description: purpose, main duties (one per line), education, experience, skills and working conditions, with its family, who it reports to and the grades it is paid in.
- The grades give the legal category (worker, supervisor, manager). A contract whose grade is outside the range gets a warning.
- An unskilled job can only have a trial of one month at most (DRC Labour Code art. 43): a longer trial is refused.
- Each save keeps a version. A contract keeps the version in force when it was signed; the person's PDF job description and the job description fields of contract templates use that version.
- Employees find their job description under My documents.

**Career ladders**
- For a job, the possible next jobs, with the minimum years in the job and the minimum average of the approved evaluations of the last two years. A training or diploma required is checked by hand.
- The person's page shows the next steps and which conditions are met; the Ready for promotion tab lists who meets them all.
- A ladder promotes nobody: the promotion goes through Career moves on the person's page, with its amendment.

**Approved positions**
- For each year, the number of posts of each job in each unit, with an optional budget, against the people in place and what was paid.
- Start from the year before or from the people in place, then adjust.
- Hiring or moving someone into a unit with no free post gives a warning; HR can go on. With no posts entered for the year, nothing is checked.` },
  },
  {
    id: 'objectives', audience: 'everyone', routes: ['/objectives'],
    fr: { title: 'Objectifs', body: `
**Pour l'employé**
- Votre responsable fixe vos objectifs de l'année : ce qui est attendu, comment c'est mesuré, la cible, l'échéance et le poids de chaque objectif.
- Lisez-les sous « Mes objectifs » et confirmez-en la prise de connaissance, avec un commentaire si vous le souhaitez. Si un objectif change, il vous est demandé de le confirmer à nouveau.

**Pour le responsable**
- Sous « Mon équipe », ouvrez une personne et ajoutez ses objectifs. Ils restent en brouillon jusqu'au partage, qui exige des poids totalisant 100 %.
- Vers la mi-année, faites un point d'étape pour chaque objectif : avancement, commentaire et, si besoin, un besoin de développement (une formation par exemple). Un rappel vous est envoyé en juillet s'il en manque.
- Un objectif partagé n'est pas supprimé mais annulé : il reste dans l'historique.
- Les objectifs d'unité, fixés par les RH ou le chef de l'unité, servent de point de départ : l'objectif d'une personne peut en découler.

**Dans l'évaluation**
- Quand le modèle d'évaluation comprend le critère « Objectifs individuels », chaque objectif partagé y est noté de 0 à 100. La moyenne pondérée devient la note de ce critère, avec le poids fixé dans le modèle (par exemple 40 % pour les cadres).
- Sans objectif partagé pour l'année, le responsable note le critère à la main.
- Des objectifs écrits et leurs points d'étape sont aussi la preuve attendue en cas de licenciement pour insuffisance professionnelle (Code du travail RDC art. 62 et 72).

**Pour les RH**
- La « Vue d'ensemble » montre, par unité, qui a des objectifs, qui les a confirmés, les points d'étape faits et les objectifs atteints (avancement de 100 % ou note d'au moins 75), puis les personnes sans objectifs, les points d'étape en retard et les besoins de développement.` },
    en: { title: 'Objectives', body: `
**For employees**
- Your manager sets your objectives for the year: what is expected, how it is measured, the target, the due date and the weight of each objective.
- Read them under "My objectives" and acknowledge them, with a comment if you wish. If an objective changes, you are asked to acknowledge it again.

**For managers**
- Under "My team", open a person and add their objectives. They stay as drafts until you share them, which needs weights that add up to 100%.
- Around mid-year, record a check-in for each objective: progress, a comment and, if needed, a development need (a training, for example). A reminder comes in July if some are missing.
- A shared objective is not deleted but cancelled, so it stays in the history.
- Unit objectives, set by HR or the unit's head, are the starting point: a person's objective can follow one.

**In the evaluation**
- When the evaluation template has the "Individual objectives" criterion, each shared objective is scored from 0 to 100. The weighted average becomes that criterion's score, with the weight set in the template (for example 40% for managers).
- With no objective shared for the year, the manager scores the criterion by hand.
- Written objectives and their check-ins are also the evidence expected if a dismissal for poor work is ever contested (DRC Labour Code art. 62 and 72).

**For HR**
- The "Overview" shows, by unit, who has objectives, who acknowledged them, the check-ins done and the objectives reached (100% progress or a score of 75 or more), then the people with no objectives, late check-ins and development needs.` },
  },
  {
    id: 'recruitment', audience: 'managers', routes: ['/recruitment'],
    fr: { title: 'Recrutement', body: `
**Demander un recrutement (chef d'unité)**
- Sous « Demandes de recrutement », choisissez l'unité, l'emploi, le nombre de postes, le motif et le contrat. S'il n'y a plus de poste approuvé libre dans l'unité, un avertissement s'affiche, et les RH le voient.
- Les RH approuvent ou refusent. Une demande approuvée devient un poste à pourvoir en brouillon, avec une annonce reprise de la fiche de poste.

**Le poste à pourvoir (RH)**
- Relisez l'annonce avant de l'ouvrir. HR Pay signale les mots qui pourraient exclure quelqu'un pour son sexe, son âge, son état civil, sa religion, son origine, sa nationalité, une grossesse ou un handicap (AM 012/2008 art. 15 ; Code du travail art. 1 et 128). Aucun frais ne peut être demandé aux candidats.
- En RDC, déclarez le poste vacant au service de placement (ONEM) et saisissez la date : un rappel arrive deux jours après l'ouverture si elle manque (AD 87/005).
- Ajoutez les candidats reçus par e-mail, recommandation ou agence, avec leur CV (PDF ou Word, 5 Mo au plus). Une même personne qui postule deux fois garde un seul dossier.

**La page carrières et les offres internes**
- Sous « Page carrières », choisissez un nom court (par exemple ntoprog) et activez la page : elle est publique à l'adresse …/careers/ntoprog, avec votre logo. Elle montre les postes « publics », et ceux « d'abord le personnel » une fois leurs jours réservés passés.
- Les candidats y postulent sans compte et sans frais, avec leur CV ; ils acceptent que le dossier soit gardé et reçoivent un e-mail avec un lien pour retirer leur candidature et effacer leurs données.
- Sur un poste ouvert, « Diffuser l'offre » donne des liens pour WhatsApp, LinkedIn ou un site d'emploi : la provenance de chaque candidat est notée.
- Le personnel voit tous les postes ouverts dans « Offres d'emploi » et postule en un clic. Vous voyez son unité, son grade, son ancienneté dans l'emploi, sa moyenne d'évaluation et si le parcours de carrière mène à ce poste. Son responsable n'est prévenu qu'à la présélection.
- Un membre du personnel retenu n'est pas engagé une seconde fois : « Affecter à ce poste » fait une promotion, une mutation ou les deux, avec l'avenant à signer.

**Les candidats**
- Faites avancer chaque candidat : présélectionné, entretien, offre, engagé ; ou gardé pour plus tard, retiré, non retenu. Un candidat non retenu reçoit un court e-mail courtois, sauf si vous dites non.
- Planifiez un entretien avec un jury : chaque membre est prévenu et remplit sa grille, compétence par compétence de 1 à 5. Un membre ne voit les notes des autres qu'après avoir donné les siennes. Ne notez jamais le sexe, l'âge, la famille, la religion ou l'origine.

**L'offre et l'engagement**
- L'offre fixe le grade, le net mensuel, la date d'entrée, la date de fin pour un CDD et la période d'essai (au plus 1 mois pour un manœuvre, 6 mois sinon, art. 43). La lettre d'offre (PDF) se rédige à partir de ces conditions ; vous pouvez la modifier.
- Une fois l'offre acceptée, « Engager » crée l'employé dans l'unité du poste avec son premier contrat, ouvre la liste d'embauche et lance la déclaration d'embauche à l'ONEM (48 heures).
- Avant l'engagement : pas d'embauche avant 15 ans ; de 15 à 17 ans, la dérogation du Président du Tribunal de paix est exigée (art. 6 et 133). Un étranger a besoin d'une carte de travail, et le poste doit être resté vacant 30 jours après sa déclaration. Le certificat d'aptitude médicale (art. 38), la remise du projet de contrat deux jours ouvrables avant la signature (art. 46) et le visa de l'ONEM (art. 47) restent suivis sur le contrat.
- Quand tous les postes sont pourvus, le poste se ferme et les autres candidats sont prévenus.

**Données des candidats**
- Le dossier d'un candidat est supprimé 12 mois après la fermeture de son dernier poste, sauf s'il a accepté d'être gardé pour d'autres offres.` },
    en: { title: 'Recruitment', body: `
**Asking to hire (unit heads)**
- Under "Requests to hire", choose the unit, the job, the number of posts, the reason and the contract. If the unit has no free approved post left, a warning shows, and HR sees it.
- HR approves or refuses. An approved request becomes a draft vacancy, with an ad taken from the job description.

**The vacancy (HR)**
- Read the ad before opening it. HR Pay flags words that could exclude someone for their sex, age, marital status, religion, origin, nationality, a pregnancy or a disability (AM 012/2008 art. 15; Labour Code art. 1 and 128). Candidates can never be asked to pay.
- In the DRC, declare the vacancy to the placement office (ONEM) and enter the date: a reminder comes two days after opening if it is missing (AD 87/005).
- Add candidates received by email, referral or agency, with their CV (PDF or Word, 5 MB at most). The same person applying twice keeps one file.

**Careers page and internal offers**
- Under "Careers page", choose a short name (for example ntoprog) and turn the page on: it is public at …/careers/ntoprog, with your logo. It shows the "public" vacancies, and the "staff first" ones once their staff-only days have passed.
- Candidates apply there with no account and no fee, with their CV; they agree to their file being kept and get an email with a link to withdraw and delete their data.
- On an open vacancy, "Share the offer" gives links for WhatsApp, LinkedIn or a job board: where each candidate came from is recorded.
- Staff see every open vacancy under "Job offers" and apply in one click. You see their unit, grade, time in the job, evaluation average and whether the career ladder leads to this job. Their manager is told only at the shortlist.
- A staff member who is selected is not hired again: "Move to this post" makes a promotion, a transfer or both, with the avenant to sign.

**Candidates**
- Move each candidate along: shortlisted, interview, offer, hired; or kept for later, withdrew, not selected. A candidate not selected gets a short, polite email unless you say no.
- Plan an interview with a panel: each member is told and fills their scorecard, skill by skill from 1 to 5. A member sees the others' scores only after giving their own. Never score sex, age, family, religion or origin.

**Offer and hire**
- The offer sets the grade, the monthly net, the start date, the end date for a fixed-term contract and the trial period (at most 1 month for an unskilled labourer, 6 months otherwise, art. 43). The offer letter (PDF) is written from these terms; you can change it.
- Once the offer is accepted, "Hire" creates the employee in the vacancy's unit with their first contract, opens the hiring checklist and starts the hire declaration to ONEM (48 hours).
- Before hiring: nobody under 15; from 15 to 17, the waiver of the Président du Tribunal de paix is needed (art. 6 and 133). A foreigner needs a work card, and the post must have stayed unfilled 30 days after it was declared. The medical fitness certificate (art. 38), the draft contract given two working days before signing (art. 46) and the ONEM visa (art. 47) are still followed on the contract.
- When every post is filled, the vacancy closes and the other candidates are told.

**Candidate data**
- A candidate's file is deleted 12 months after their last vacancy closes, unless they agreed to be kept for later offers.` },
  },
  {
    id: 'onboarding', audience: 'hr', routes: ['/onboarding', '/welcome'],
    fr: { title: 'Accueil des nouveaux', body: `
- Embaucher un candidat ouvre l'accueil : la liste d'embauche (chaque étape avec son responsable et sa date), le lien du formulaire et les bilans d'essai. Les RH peuvent aussi ouvrir un accueil pour une personne embauchée sans le recrutement (« Ouvrir un accueil »).
- **Avant le premier jour** : « Envoyer le lien » envoie au nouveau, par e-mail, un lien personnel sans compte. Il y remplit son identité, sa pièce d'identité, son numéro CNSS, son mode de paiement et sa famille, et envoie ses pièces (PDF, JPEG ou PNG). Le lien fonctionne jusqu'au premier jour ; un nouveau lien remplace l'ancien.
- Les informations sont chiffrées. Vérifiez chaque pièce : acceptée, elle va dans le dossier de l'employé (avec sa date d'expiration si elle en a une) ; refusée, le nouveau lit le motif et peut en envoyer une autre. Puis « Reporter dans le dossier de l'employé » : les réponses vides ne changent rien, la famille s'ajoute aux ayants droit. Sans compte bancaire connu, le paiement passe en espèces jusqu'à ce qu'il soit saisi.
- **Étapes** : dans Paramètres de Embauches et départs, chaque étape de la liste d'embauche a un responsable (RH, responsable, informatique, nouveau, parrain) et une échéance en jours depuis l'entrée (-3 : trois jours avant). La veille, son responsable reçoit un rappel. Le responsable hiérarchique coche ses étapes depuis cette page.
- **Parrain** : le responsable ou les RH choisissent un collègue de l'unité, prévenu par une notification.
- **Page de bienvenue** : le premier jour, le nouveau voit sur l'accueil un lien vers sa page : son poste, son responsable, son parrain, ses premières étapes et le texte de l'entreprise (bouton « Page de bienvenue »).
- **Bilans d'essai** : avec une période d'essai au contrat, un point à un mois et un bilan deux semaines avant la fin sont prévus pour le responsable, rappelés le jour venu. Le bilan de fin demande une recommandation (confirmer ou mettre fin) qui est envoyée aux RH : mettre fin à l'essai demande une notification écrite avant son dernier jour (art. 43 du Code du travail).` },
    en: { title: 'Onboarding new hires', body: `
- Hiring a candidate opens the onboarding: the hiring checklist (each step with its owner and date), the form link and the trial reviews. HR can also open one for someone hired without the recruitment pages ("Start an onboarding").
- **Before day one**: "Send the link" emails the new hire a personal link, with no account. They fill in their identity, ID document, CNSS number, how they want to be paid and their family, and send their papers (PDF, JPEG or PNG). The link works until the first day; a new link replaces the old one.
- The answers are encrypted. Check each paper: accepted, it goes into the employee file (with its expiry date when it has one); refused, the new hire reads why and can send another. Then "Copy into the employee file": empty answers change nothing, family members are added to the dependants. With no bank account known yet, the pay is in cash until it is entered.
- **Steps**: in the settings of Hiring and departures, each hiring step has an owner (HR, manager, IT, new hire, buddy) and a due day counted from the start date (-3: three days before). The owner is reminded the day before. The manager ticks their steps from this page.
- **Buddy**: the manager or HR picks a colleague from the unit, who is told by a notification.
- **Welcome page**: on day one the new hire sees a link on their home page to their welcome page: their post, manager, buddy, first steps and the company's text ("Welcome page" button).
- **Trial reviews**: with a trial period on the contract, a one-month review and an end-of-trial review two weeks before the end are planned for the manager and reminded on the day. The end review needs a recommendation (confirm or end), which goes to HR: ending the trial needs a written notice before its last day (Labour Code art. 43).` },
  },
  {
    id: 'training', audience: 'hr', routes: ['/training', '/training/settings'],
    fr: { title: 'Formation et apprentis', body: `
- Tout employeur assure la formation de ses travailleurs (Code du travail art. 8). L'ordonnance 71-055 demande un **plan de formation** qui n'exclut aucune catégorie du personnel (art. 17), forme d'abord les cadres nationaux (art. 18), est établi avec les représentants des travailleurs (art. 19) et a un responsable désigné (art. 22).
- **Formations** : le catalogue des formations internes, INPP ou d'un cabinet externe, avec la durée et le coût par personne.
- **Besoins et demandes** : un employé demande une formation depuis « Mes formations » ; son responsable donne son avis, puis les RH acceptent ou refusent (avec un motif). Un responsable peut demander pour son équipe. « Reprendre des objectifs et évaluations » ajoute les besoins de développement notés sur les objectifs de l'année et les critères notés sous 50 % dans les évaluations approuvées.
- **Plan annuel** : un budget, des lignes (formation, catégorie, unité, trimestre, nombre de personnes, budget). Le tableau « Toutes les catégories couvertes » signale une catégorie sans ligne. L'avis des représentants est noté avec sa date ; il ne bloque pas l'approbation. La cotisation INPP payée sur les mois clôturés de l'année est affichée en face des dépenses.
- **Sessions** : inscrivez les personnes à partir de leurs besoins, puis après le dernier jour cochez les présents et notez le coût réel. Chaque présent peut noter la session ; son attestation se classe dans son dossier (Attestations de formation). Trois mois après (réglable), son responsable dit si la formation a changé son travail (onglet « Suivi des effets »).
- **Apprentis** (Code du travail art. 18-35) : créez un contrat de type Apprenti dans le dossier de la personne : quatre ans au plus, jamais avant 15 ans. Notez ensuite le visa de l'ONEM (sans lui, le contrat vaut contrat de travail ordinaire), le certificat médical, la dérogation du Tribunal de paix avant 18 ans, le maître, la revue annuelle de l'allocation, le certificat de fin et la déclaration de fin à l'inspecteur et à l'ONEM. Par défaut l'allocation ne supporte ni CNSS ni IPR (paramètre à confirmer avec votre comptable). Les modèles « Certificat de fin d'apprentissage » et « Engagement à la suite d'une formation » sont dans les lettres ; l'engagement ne permet aucune retenue sur salaire.
- **Congé d'éducation ouvrière** (art. 268-271) : un type de congé de 12 jours ouvrables par an, payé, demandé 30 jours à l'avance, pour les syndiqués et délégués.` },
    en: { title: 'Training and apprentices', body: `
- Every employer trains its workers (Labour Code art. 8). Ordonnance 71-055 asks for a **training plan** that leaves no category of staff out (art. 17), trains national managers first (art. 18), is drawn up with the workers' representatives (art. 19) and has a named person in charge (art. 22).
- **Courses**: the catalogue of in-house, INPP or outside courses, with their hours and cost per person.
- **Needs and requests**: an employee asks for training from "My training"; their manager gives an opinion, then HR accepts or refuses (with a reason). A manager can ask for their team. "Collect from objectives and evaluations" adds the development needs written on the year's objectives and the criteria scored below 50% in approved evaluations.
- **Annual plan**: a budget and lines (course, category, unit, quarter, number of people, budget). "Every category covered" flags a category with no line. The representatives' opinion is recorded with its date; it does not block the approval. The INPP levy paid on the year's closed months is shown beside the spending.
- **Sessions**: enrol people from their needs, then after the last day tick who came and enter the real cost. Each attendee can rate the session; their certificate is filed in their employee file (Training certificates). Three months later (a setting), their manager says whether it changed their work ("Effect check" tab).
- **Apprentices** (Labour Code art. 18-35): start a contract of type Apprentice on the person's file: four years at most, never under 15. Then record the ONEM visa (without it the contract is an ordinary employment contract), the medical certificate, the Tribunal de paix waiver under 18, the master, the yearly review of the allowance, the end certificate and the end reported to the inspector and ONEM. By default the allowance owes no CNSS or IPR (a setting to confirm with your accountant). The letters include an "Apprenticeship end certificate" and a "Commitment after a paid training"; the commitment allows no deduction from pay.
- **Workers' education leave** (art. 268-271): a leave type of 12 working days a year, paid, asked 30 days ahead, for union members and delegates.` },
  },
  {
    id: 'my-training', audience: 'everyone', routes: ['/my/training'],
    fr: { title: 'Mes formations', body: `
- « Mes formations » montre les sessions à venir, vos demandes et votre historique, avec les heures suivies cette année.
- « Demander une formation » : choisissez une formation du catalogue ou décrivez le besoin et dites pourquoi. Votre responsable donne son avis, puis les RH décident ; vous êtes prévenu(e).
- Après une session, notez-la : cela aide les RH à choisir les prochaines. Votre attestation, quand il y en a une, est dans « Mes documents ».` },
    en: { title: 'My training', body: `
- "My training" shows your coming sessions, your requests and your history, with the hours followed this year.
- "Ask for training": choose a course from the catalogue or describe the need and say why. Your manager gives an opinion, then HR decides; you are told.
- After a session, rate it: it helps HR choose the next ones. Your certificate, when there is one, is in "My documents".` },
  },
  {
    id: 'skills', audience: 'hr', routes: ['/skills'],
    fr: { title: 'Compétences', body: `
- **Catalogue** : les compétences, par famille (techniques, comportementales, langues, outils). Chacune a quatre niveaux : 1 notions, 2 praticien, 3 confirmé, 4 expert, avec une ligne qui décrit chaque niveau. Un catalogue de départ est fourni ; renommez, ajoutez ou mettez de côté.
- **Compétences par emploi** : pour chaque emploi, les compétences demandées et le niveau. Le texte libre de la fiche de poste reste affiché pour vous aider.
- **Niveaux des personnes** : l'employé s'évalue dans « Mes compétences » ; son responsable confirme ou corrige depuis l'onglet « À confirmer » ou la fiche de la personne. Seul le niveau confirmé compte. Les RH peuvent fixer un niveau directement, par exemple après un test.
- **Matrice des écarts** : chaque personne face aux compétences que demande son emploi (vert atteint, orange un niveau en dessous, rouge plus). « Transformer les écarts en besoins de formation » ajoute les besoins de l'année dans Formation, avec une formation qui développe la compétence quand le catalogue en a une (case « Compétences développées » d'une formation).
- **Rechercher** : qui a une compétence à un niveau donné ou plus, pour trouver un formateur, un successeur ou un candidat interne.
- **Permis et certifications** : ajoutez-les dans les documents de la personne avec ce type et une date d'expiration ; les RH sont prévenus avant l'échéance comme pour les autres documents.` },
    en: { title: 'Skills', body: `
- **Catalogue**: the skills, by family (technical, behavioural, languages, tools). Each has four levels: 1 notions, 2 practitioner, 3 confirmed, 4 expert, with a line describing each level. A starting catalogue is provided; rename, add or put aside.
- **Skills per job**: for each job, the skills it needs and the level. The free text of the job description stays shown to help you.
- **People's levels**: the employee rates themselves in "My skills"; their manager confirms or corrects from the "To confirm" tab or the person's page. Only the confirmed level counts. HR can set a level directly, for example after a test.
- **Gap matrix**: each person against the skills their job needs (green reached, amber one level short, red more). "Turn the gaps into training needs" adds the year's needs in Training, with a course that builds the skill when the catalogue has one (a course's "Skills it builds").
- **Search**: who has a skill at a level or more, to find a trainer, a successor or an internal candidate.
- **Licences and certifications**: add them in the person's documents with this type and an expiry date; HR is reminded before they expire, like other documents.` },
  },
  {
    id: 'my-skills', audience: 'everyone', routes: ['/my/skills'],
    fr: { title: 'Mes compétences', body: `
- « Mes compétences » liste les compétences que demande votre emploi, avec le niveau attendu.
- Donnez votre niveau pour chacune, avec un exemple si vous voulez, puis « Envoyer mon évaluation ». Vous pouvez ajouter d'autres compétences que vous avez.
- Votre responsable confirme ou corrige ; seul le niveau confirmé compte. Un écart avec le niveau attendu peut devenir un besoin de formation.
- Vos permis et certifications sont listés en bas ; ajoutez-en un dans « Mes documents ».` },
    en: { title: 'My skills', body: `
- "My skills" lists the skills your job needs, with the level expected.
- Give your level for each, with an example if you like, then "Send my ratings". You can add other skills you have.
- Your manager confirms or corrects; only the confirmed level counts. A gap with the expected level can become a training need.
- Your licences and certifications are listed at the bottom; add one in "My documents".` },
  },
  {
    id: 'job-offers', audience: 'everyone', routes: ['/job-offers'],
    fr: { title: 'Offres d’emploi internes', body: `
- « Offres d'emploi » montre les postes ouverts dans l'entreprise, y compris ceux réservés au personnel.
- Ouvrez une offre pour lire l'annonce, puis « Postuler », avec un mot pour les RH si vous le souhaitez. Les RH voient votre dossier.
- Votre responsable n'est prévenu que si les RH vous présélectionnent, pas au moment où vous postulez.
- Vous suivez où en est votre candidature sur la même page, et vous pouvez la retirer tant qu'aucune décision n'est prise.
- Si vous êtes retenu(e), vous recevez un avenant (promotion ou mutation) à signer : vous gardez votre ancienneté.` },
    en: { title: 'Internal job offers', body: `
- "Job offers" shows the open posts in the company, including those for staff only.
- Open an offer to read the ad, then "Apply", with a word for HR if you like. HR sees your file.
- Your manager is told only if HR shortlists you, not when you apply.
- You follow where your application stands on the same page, and can withdraw it while no decision is made.
- If you are selected, you get an avenant (promotion or transfer) to sign: you keep your seniority.` },
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
  {
    id: 'email-server', audience: 'admin', routes: ['/company/email', '/platform/email', '/outbox'],
    fr: { title: 'Configurer le serveur d’e-mail', body: `
HR Pay envoie par e-mail les codes de pointage et de connexion, les liens de mot de passe, les augmentations et les alertes. Pour chaque e-mail, il prend le premier serveur défini et activé :

1. **Le serveur de votre entreprise** (Entreprise, puis Serveur d'e-mail) : les e-mails partent de votre domaine.
2. **Le serveur de HR Pay**, défini par le fournisseur : l'expéditeur affiche « Votre entreprise via HR Pay ».
3. **Le fichier .env** du serveur (installations sur site).
4. **Aucun** : les e-mails ne partent pas ; ils sont seulement inscrits sur la page **Messages** (« LOGGED »).

Les réglages : protocole (SMTP, ou SMTPS dès la connexion), hôte, port (587 pour STARTTLS, 465 pour SSL/TLS), chiffrement, adresse et nom de l'expéditeur, et en option la copie cachée (BCC), l'identifiant et le mot de passe, et le domaine EHLO (exigé par certains relais, comme smtp-relay.gmail.com).

- **Tester la connexion** vérifie l'hôte, le port et le mot de passe sans rien envoyer ; **M'envoyer un e-mail de test** vous en envoie un.
- Le mot de passe est enregistré chiffré et n'est plus jamais affiché ; laissez le champ vide pour le garder.
- La copie cachée ne reçoit jamais les codes de connexion ni les liens de mot de passe.
- Si votre serveur est en échec, les e-mails ne partent pas par un autre serveur : ils apparaissent « FAILED » sur la page Messages, et les personnes qui gèrent l'entreprise sont prévenues dans l'application (au plus une fois par heure).` },
    en: { title: 'Setting up the email server', body: `
HR Pay emails check-in and sign-in codes, password links, raises and alerts. For each email it takes the first server that is set and turned on:

1. **Your company's server** (Company, then Email server): emails leave from your own domain.
2. **HR Pay's server**, set by the provider: the sender shows "Your company via HR Pay".
3. **The server's .env file** (on-premise installations).
4. **None**: emails do not leave; they are only written on the **Messages** page ("LOGGED").

The settings: protocol (SMTP, or SMTPS from the first byte), host, port (587 for STARTTLS, 465 for SSL/TLS), encryption, sender address and name, and optionally the hidden copy (BCC), username and password, and the EHLO domain (some relays, such as smtp-relay.gmail.com, ask for it).

- **Test the connection** checks the host, port and password without sending anything; **Send me a test email** sends you one.
- The password is stored encrypted and never shown again; leave the field empty to keep it.
- The hidden copy never gets sign-in codes or password links.
- When your server fails, emails are not sent another way: they show as "FAILED" on the Messages page, and the people who manage the company are told in the app (at most once an hour).` },
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
