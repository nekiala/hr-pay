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
- **Mon dossier** : les documents que les RH gardent pour vous, et ceux que vous leur envoyez.` },
    en: { title: 'Signing a document and asking for a certificate', body: `
In **My documents**:

- **To sign**: read the PDF, ask for a code (sent by email or WhatsApp), enter it with your full name. The signed copy goes into your file. You can also refuse and give the reason.
- **Certificates**: ask for an employment or salary certificate; you are told when it is ready.
- **My file**: the documents HR keeps for you, and the ones you send them.` },
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
- **Réglages** (bouton Paramètres) : les types de documents gardés pour chaque personne, les étapes de chaque liste, et les modèles de contrats et de lettres signés par code. Les textes de départ sont des brouillons : faites-les relire par votre avocat.` },
    en: { title: 'Hiring and departures', body: `
- **To do**: open checklists, certificates asked for, letters waiting for a signature, documents about to expire and equipment still out with people leaving.
- A departure checklist starts by itself when a departure is confirmed; start a hiring checklist from the person's page.
- **Settings** (Settings button): the document types kept for each person, the steps of each checklist, and the contract and letter templates signed with a code. The starting texts are drafts: have them checked by your lawyer.` },
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
