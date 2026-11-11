/*
 * The salary calculator: asks the HR Pay server (GET /api/public/calculator), which works the month out with the
 * payroll engine and the country's official rates. Nothing is stored unless the visitor asks for the result by email.
 *
 * <div id="calculator" data-country="CD" data-countries="CD,CG" data-focus="net|cost"></div>
 */
(function () {
  var root = document.getElementById('calculator');
  if (!root) { return; }
  var H = window.HRPay;
  var countries = (root.dataset.countries || root.dataset.country || 'CD').split(',');
  var focus = root.dataset.focus || 'net';

  var T = {
    fr: {
      gross: 'Du brut au net', net: 'Du net au brut', country: 'Pays', CD: 'RDC', CG: 'République du Congo', CM: 'Cameroun', GA: 'Gabon',
      amountGross: 'Salaire de base brut, par mois', amountNet: 'Net à payer souhaité, par mois',
      currency: 'Monnaie', housing: 'Indemnité de logement', transport: 'Indemnité de transport',
      family: 'Allocations familiales', bonus: 'Primes imposables', children: 'Enfants à charge',
      marital: 'Situation de famille', SINGLE: 'Célibataire', MARRIED: 'Marié(e)', DIVORCED: 'Divorcé(e)', WIDOWED: 'Veuf ou veuve',
      spouse: 'Conjoint à charge', employees: "Nombre de salariés de l'entreprise", employeesHelp: 'Fixe le taux INPP.',
      expatriate: 'Salarié expatrié (IERE)', more: 'Indemnités, famille et entreprise', perMonth: 'par mois',
      calculate: 'Calculer', working: 'Calcul…', netPay: 'Net à payer', grossPay: 'Salaire brut', cost: 'Coût employeur',
      line: 'Élément', base: 'Base', amount: 'Montant', earnings: 'Gains', deductions: 'Retenues salariales',
      charges: 'Charges patronales', totalDeductions: 'Total des retenues', totalCharges: 'Total des charges',
      below: 'Le salaire de base est sous le minimum légal ({min}). Un employeur ne peut pas payer moins.',
      ratesOn: 'Taux en vigueur le {d}.', rateUsd: '1 USD = {r} {cur} (BCC, {d}).',
      assumes: 'Mois complet travaillé, sans heures supplémentaires ni prêts. Résultat indicatif.',
      sources: 'Taux utilisés', trial: 'Faites-le pour tout votre personnel', trialHelp: 'Essai gratuit, pointage et déclarations compris.',
      mailTitle: 'Recevoir ce calcul par email', email: 'Email', company: 'Entreprise (facultatif)', phone: 'Téléphone (facultatif)',
      size: 'Taille', send: 'Envoyer', sent: 'Envoyé. Vérifiez votre boîte de réception.',
      mailNote: 'Nous gardons votre email pour vous recontacter au sujet de HR Pay, rien d\'autre.',
      offline: 'Le calculateur ne répond pas pour le moment. Réessayez dans un instant.',
      tooMany: 'Trop de calculs à la suite. Attendez une minute.',
      noRate: 'Le taux du dollar n\'est pas encore connu : saisissez les montants en {cur}.'
    },
    en: {
      gross: 'Gross to net', net: 'Net to gross', country: 'Country', CD: 'DR Congo', CG: 'Republic of the Congo', CM: 'Cameroon', GA: 'Gabon',
      amountGross: 'Gross basic salary, per month', amountNet: 'Wanted net pay, per month',
      currency: 'Currency', housing: 'Housing allowance', transport: 'Transport allowance',
      family: 'Family allowances', bonus: 'Taxable bonuses', children: 'Children in charge',
      marital: 'Marital status', SINGLE: 'Single', MARRIED: 'Married', DIVORCED: 'Divorced', WIDOWED: 'Widowed',
      spouse: 'Spouse in charge', employees: 'Number of employees in the company', employeesHelp: 'Sets the INPP rate.',
      expatriate: 'Expatriate employee (IERE)', more: 'Allowances, family and company', perMonth: 'per month',
      calculate: 'Calculate', working: 'Working…', netPay: 'Net pay', grossPay: 'Gross salary', cost: 'Employer cost',
      line: 'Item', base: 'Base', amount: 'Amount', earnings: 'Earnings', deductions: 'Employee deductions',
      charges: 'Employer charges', totalDeductions: 'Total deductions', totalCharges: 'Total charges',
      below: 'The basic salary is below the legal minimum ({min}). An employer may not pay less.',
      ratesOn: 'Rates in force on {d}.', rateUsd: '1 USD = {r} {cur} (BCC, {d}).',
      assumes: 'A full month worked, without overtime or loans. A guide, not a payslip.',
      sources: 'Rates used', trial: 'Do it for all your staff', trialHelp: 'Free trial, attendance and declarations included.',
      mailTitle: 'Get this calculation by email', email: 'Email', company: 'Company (optional)', phone: 'Phone (optional)',
      size: 'Size', send: 'Send', sent: 'Sent. Check your inbox.',
      mailNote: 'We keep your email to contact you about HR Pay, nothing else.',
      offline: 'The calculator is not answering right now. Try again in a moment.',
      tooMany: 'Too many calculations in a row. Wait a minute.',
      noRate: 'The dollar rate is not known yet: enter the amounts in {cur}.'
    }
  };
  var LINES = {
    BASIC: ['Salaire de base', 'Basic salary'], HOUSING: ['Indemnité de logement', 'Housing allowance'],
    TRANSPORT: ['Indemnité de transport', 'Transport allowance'], FAMILY_ALLOWANCE: ['Allocations familiales', 'Family allowances'],
    OTHER_BONUS: ['Primes', 'Bonuses'], CNSS_EMPLOYEE: ['CNSS, part salariale', 'CNSS, employee share'],
    IPR: ['IPR (impôt sur le revenu)', 'IPR (income tax)'], CNSS_EMPLOYER: ['CNSS, part patronale', 'CNSS, employer share'],
    INPP: ['INPP (formation professionnelle)', 'INPP (vocational training)'], ONEM: ['ONEM (office de l\'emploi)', 'ONEM (employment office)'],
    IERE: ['IERE (expatriés)', 'IERE (expatriates)'], TUS: ['TUS (taxe unique sur les salaires)', 'TUS (single tax on salaries)'],
    CNSS_EMPLOYER_FAMILY: ['CNSS, prestations familiales', 'CNSS, family benefits'],
    CNSS_EMPLOYER_ACCIDENT: ['CNSS, accidents du travail', 'CNSS, work accidents']
  };
  var CEMAC_LINES = { IPR: ['IRPP (impôt sur le revenu des personnes physiques)', 'IRPP (personal income tax)'],
    CNSS_EMPLOYEE: ['Pension vieillesse, part salariale', 'Old-age pension, employee share'],
    CNSS_EMPLOYER: ['Pension vieillesse, part patronale', 'Old-age pension, employer share'],
    CAC: ['CAC (centimes additionnels communaux)', 'CAC (additional council tax)'],
    CFC_EMPLOYEE: ['Crédit foncier, part salariale', 'Crédit foncier, employee share'],
    CFC_EMPLOYER: ['Crédit foncier, part patronale', 'Crédit foncier, employer share'],
    FNE: ['FNE (fonds national de l\'emploi)', 'FNE (national employment fund)'],
    RAV: ['RAV (redevance audiovisuelle)', 'RAV (audiovisual licence fee)'],
    TDL: ['TDL (taxe de développement local)', 'TDL (local development tax)'],
    CNAMGS_EMPLOYEE: ['CNAMGS, part salariale', 'CNAMGS, employee share'],
    CNAMGS_EMPLOYER: ['CNAMGS, part patronale', 'CNAMGS, employer share'],
    TCS: ['TCS (taxe complémentaire sur les salaires)', 'TCS (additional tax on salaries)'],
    FNH: ['FNH (fonds national de l\'habitat)', 'FNH (national housing fund)'],
    CFP: ['Formation professionnelle', 'Vocational training'] };
  var CONGO_LINES = { IPR: ['ITS (impôt sur les traitements et salaires)', 'ITS (tax on salaries)'],
    CNSS_EMPLOYEE: ['CNSS pensions, part salariale', 'CNSS pensions, employee share'],
    CNSS_EMPLOYER: ['CNSS pensions, part patronale', 'CNSS pensions, employer share'],
    CAMU_SOLIDARITY: ['CAMU, contribution de solidarité', 'CAMU solidarity contribution'],
    TOL: ['TOL (taxe d\'occupation des locaux)', 'TOL (premises occupation tax)'] };
  var SOURCES = {
    CNSS_EMPLOYEE: ['CNSS salarié', 'CNSS employee'], CNSS_EMPLOYER: ['CNSS employeur', 'CNSS employer'],
    INPP_SMALL: ['INPP, jusqu\'à 50 salariés', 'INPP, up to 50 staff'], INPP_MEDIUM: ['INPP, 51 à 300 salariés', 'INPP, 51 to 300 staff'],
    INPP_LARGE: ['INPP, plus de 300 salariés', 'INPP, above 300 staff'], ONEM: ['ONEM', 'ONEM'], IERE: ['IERE', 'IERE'],
    IPR_DEPENDANT_REDUCTION: ['Réduction IPR par personne à charge', 'IPR reduction per dependant'],
    IPR_MAX_DEPENDANTS: ['Personnes à charge comptées, au plus', 'Dependants counted, at most'],
    IPR_CAP: ['Plafond de l\'IPR', 'IPR cap'], IPR_MIN_MONTHLY: ['IPR minimum par mois', 'IPR minimum per month'],
    SMIG_DAILY: ['SMIG journalier', 'Daily minimum wage (SMIG)'], SMIG_MONTHLY: ['SMIG mensuel', 'Monthly minimum wage (SMIG)'],
    CNSS_CEILING: ['Plafond CNSS pensions', 'CNSS pension ceiling'], CNSS_FAMILY: ['CNSS prestations familiales', 'CNSS family benefits'],
    CNSS_ACCIDENT: ['CNSS accidents du travail', 'CNSS work accidents'], CNSS_FAMILY_CEILING: ['Plafond CNSS famille et accidents', 'CNSS family and accident ceiling'],
    TUS: ['TUS', 'TUS'], ITS_PROFESSIONAL_DEDUCTION: ['Abattement professionnel ITS', 'ITS professional deduction'],
    ITS_MAX_PARTS: ['Parts ITS, au plus', 'ITS parts, at most'], ITS_FIRST_BAND_FLAT: ['ITS, première tranche par part et par an', 'ITS, first band per part and year'],
    ITS_MINIMUM_YEARLY: ['ITS minimum par an', 'ITS minimum per year'],
    CAMU_SOLIDARITY: ['CAMU, contribution de solidarité', 'CAMU solidarity contribution'],
    CAMU_SOLIDARITY_THRESHOLD: ['CAMU solidarité : seuil mensuel', 'CAMU solidarity: monthly threshold'],
    TOL_MONTHLY: ['TOL par mois', 'TOL per month'],
    CNAMGS_EMPLOYEE: ['CNAMGS salarié', 'CNAMGS employee'], CNAMGS_EMPLOYER: ['CNAMGS employeur', 'CNAMGS employer'],
    CNAMGS_CEILING: ['Plafond CNAMGS', 'CNAMGS ceiling'], CFC_EMPLOYEE: ['Crédit foncier salarié', 'Crédit foncier employee'],
    CFC_EMPLOYER: ['Crédit foncier employeur', 'Crédit foncier employer'], FNE: ['FNE', 'FNE'], CAC: ['CAC', 'CAC'],
    TCS: ['TCS', 'TCS'], TCS_THRESHOLD: ['Seuil de la TCS', 'TCS threshold'], FNH: ['FNH', 'FNH'], CFP: ['Formation professionnelle', 'Vocational training'],
    TAX_PROFESSIONAL_DEDUCTION: ['Abattement professionnel IRPP', 'IRPP professional deduction'],
    TAX_YEARLY_ALLOWANCE: ['Abattement annuel IRPP', 'IRPP yearly allowance'], TAX_EXEMPT_BELOW: ['IRPP : pas d\'impôt sous', 'IRPP: no tax below'],
    TAX_MAX_PARTS: ['Parts IRPP, au plus', 'IRPP parts, at most']
  };
  var PERCENT = /^(CNSS_EMPLOYEE|CNSS_EMPLOYER|INPP_|ONEM|IERE|IPR_DEPENDANT_REDUCTION|IPR_CAP|CNSS_FAMILY$|CNSS_ACCIDENT|TUS|ITS_PROFESSIONAL|CNAMGS_EMPLOY|CFC_|FNE$|CAC$|TCS$|FNH$|CFP$|TAX_PROFESSIONAL|CAMU_SOLIDARITY$)/;
  var LOCAL = { CD: 'CDF', CG: 'XAF', CM: 'XAF', GA: 'XAF' };
  var SAMPLE = { CD: '1500000', CG: '400000', CM: '300000', GA: '600000' };

  var state = { country: countries.indexOf(root.dataset.country) >= 0 ? root.dataset.country : countries[0],
    mode: 'GROSS', last: null, ask: null };

  function t(k, v) {
    var s = T[H.lang()][k] || k;
    if (v) { Object.keys(v).forEach(function (n) { s = s.replace('{' + n + '}', v[n]); }); }
    return s;
  }
  function fmt(n, cur) {
    var s = new Intl.NumberFormat(H.lang() === 'fr' ? 'fr-FR' : 'en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
    return cur ? s + ' ' + cur : s;
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function lineName(code) {
    var cemac = state.country === 'CM' || state.country === 'GA';
    var l = state.country === 'CG' && CONGO_LINES[code] || cemac && CEMAC_LINES[code] || LINES[code];
    return l ? l[H.lang() === 'fr' ? 0 : 1] : code;
  }
  function el(id) { return document.getElementById('calc-' + id); }

  function render() {
    var cg = state.country !== 'CD', cur = LOCAL[state.country];   // no INPP or IERE outside the DRC
    var keep = state.ask;
    root.innerHTML =
      '<div class="calc">' +
      '<form class="card" id="calc-form" novalidate>' +
        '<div class="seg" role="group">' +
          '<button type="button" data-mode="GROSS" aria-pressed="' + (state.mode === 'GROSS') + '">' + t('gross') + '</button>' +
          '<button type="button" data-mode="NET" aria-pressed="' + (state.mode === 'NET') + '">' + t('net') + '</button>' +
        '</div>' +
        (countries.length > 1 ? '<label class="field">' + t('country') + '<select id="calc-country">' +
          countries.map(function (c) { return '<option value="' + c + '"' + (c === state.country ? ' selected' : '') + '>' + t(c) + '</option>'; }).join('') +
          '</select></label>' : '') +
        '<div class="row2">' +
          '<label class="field">' + t(state.mode === 'NET' ? 'amountNet' : 'amountGross') +
            '<input id="calc-amount" inputmode="decimal" autocomplete="off" required></label>' +
          '<label class="field">' + t('currency') + '<select id="calc-currency"><option>' + cur + '</option><option>USD</option></select></label>' +
        '</div>' +
        '<details class="more"' + (focus === 'cost' ? ' open' : '') + '><summary>' + t('more') + '</summary><div style="display:grid;gap:12px">' +
          '<div class="row2">' +
            '<label class="field">' + t('housing') + ' <small>' + t('perMonth') + '</small><input id="calc-housing" inputmode="decimal" autocomplete="off"></label>' +
            '<label class="field">' + t('transport') + ' <small>' + t('perMonth') + '</small><input id="calc-transport" inputmode="decimal" autocomplete="off"></label>' +
            '<label class="field">' + t('family') + ' <small>' + t('perMonth') + '</small><input id="calc-family" inputmode="decimal" autocomplete="off"></label>' +
            '<label class="field">' + t('bonus') + ' <small>' + t('perMonth') + '</small><input id="calc-bonus" inputmode="decimal" autocomplete="off"></label>' +
          '</div>' +
          '<div class="row2">' +
            '<label class="field">' + t('marital') + '<select id="calc-marital">' +
              ['SINGLE', 'MARRIED', 'DIVORCED', 'WIDOWED'].map(function (m) { return '<option value="' + m + '">' + t(m) + '</option>'; }).join('') +
            '</select></label>' +
            '<label class="field">' + t('children') + '<input id="calc-children" type="number" min="0" max="20" value="0"></label>' +
          '</div>' +
          '<label class="check"><input type="checkbox" id="calc-spouse"> ' + t('spouse') + '</label>' +
          (cg ? '' : '<div class="row2"><label class="field">' + t('employees') + ' <small>' + t('employeesHelp') + '</small>' +
            '<input id="calc-employees" type="number" min="1" value="20"></label>' +
            '<label class="check" style="align-self:end"><input type="checkbox" id="calc-expatriate"> ' + t('expatriate') + '</label></div>') +
        '</div></details>' +
        '<div><button class="btn primary" type="submit" id="calc-go">' + t('calculate') + '</button></div>' +
        '<p class="note">' + t('assumes') + '</p>' +
      '</form>' +
      '<div class="card result" id="calc-result" aria-live="polite"></div>' +
      '</div>';

    if (keep) {
      el('amount').value = keep.amount; el('currency').value = keep.currency;
      ['housing', 'transport', 'family', 'bonus'].forEach(function (k) { el(k).value = keep[k] || ''; });
      el('marital').value = keep.marital; el('children').value = keep.children; el('spouse').checked = keep.spouse;
      if (el('employees')) { el('employees').value = keep.employees || 20; el('expatriate').checked = keep.expatriate; }
    } else {
      el('amount').value = SAMPLE[state.country];
    }
    root.querySelectorAll('[data-mode]').forEach(function (b) {
      b.addEventListener('click', function () {
        if (state.mode === b.dataset.mode) { return; }
        state.ask = read();
        // Switching to "net to gross" starts from the net just found, so the visitor sees the same person both ways.
        if (state.last && b.dataset.mode === 'NET') { state.ask.amount = String(state.last.net); }
        if (state.last && b.dataset.mode === 'GROSS') { state.ask.amount = String(state.last.basic); }
        state.mode = b.dataset.mode; render(); calculate();
      });
    });
    if (el('country')) {
      el('country').addEventListener('change', function () {
        state.country = el('country').value; state.ask = null; state.last = null; render(); calculate();
      });
    }
    el('form').addEventListener('submit', function (e) { e.preventDefault(); calculate(); });
    if (state.last) { show(state.last); }
  }

  function num(v) { return String(v || '').replace(/[\s  ]/g, '').replace(',', '.'); }
  function read() {
    return { amount: el('amount').value, currency: el('currency').value, housing: el('housing').value,
      transport: el('transport').value, family: el('family').value, bonus: el('bonus').value,
      marital: el('marital').value, children: el('children').value || '0', spouse: el('spouse').checked,
      employees: el('employees') ? el('employees').value : '', expatriate: el('expatriate') ? el('expatriate').checked : false };
  }
  function params(a) {
    var p = { country: state.country, mode: state.mode, amount: num(a.amount) || '0', currency: a.currency,
      children: a.children, maritalStatus: a.marital, spouseInCharge: a.spouse };
    [['housing', 'housing'], ['transport', 'transport'], ['family', 'familyAllowances'], ['bonus', 'bonus']].forEach(function (k) {
      if (num(a[k[0]])) { p[k[1]] = num(a[k[0]]); }
    });
    if (state.country === 'CD') { p.employees = a.employees || '1'; p.expatriate = a.expatriate; }
    return p;
  }

  function calculate() {
    var a = read(); state.ask = a;
    var p = params(a);
    var box = el('result'), go = el('go');
    go.disabled = true; go.textContent = t('working');
    var q = Object.keys(p).map(function (k) { return k + '=' + encodeURIComponent(p[k]); }).join('&');
    fetch(H.api + '/api/public/calculator?' + q, { credentials: 'omit' })
      .then(function (r) {
        if (r.status === 429) { throw new Error(t('tooMany')); }
        return r.json().then(function (b) {
          if (!r.ok) {
            throw new Error(/dollar rate/.test(b.detail || '') ? t('noRate', { cur: LOCAL[state.country] }) : (b.detail || t('offline')));
          }
          return b;
        });
      })
      .then(function (b) { state.last = b; state.params = p; show(b); })
      .catch(function (e) { box.innerHTML = '<p class="alert">' + esc(e.message && !/fetch|network/i.test(e.message) ? e.message : t('offline')) + '</p>'; })
      .then(function () { go.disabled = false; go.textContent = t('calculate'); });
  }

  function show(b) {
    var cur = b.currency, fr = H.lang() === 'fr';
    function rows(kind) {
      return b.lines.filter(function (l) { return l.kind === kind; }).map(function (l) {
        var rate = l.rate != null && kind !== 'EARNING' ? ' <span class="rate">' + fmt(l.rate) + ' %</span>' : '';
        return '<tr><td>' + esc(lineName(l.code)) + rate + '</td><td class="num">' + fmt(l.amount) + '</td></tr>';
      }).join('');
    }
    var sumDed = b.employeeDeductions, sumCharges = b.employerCharges;
    var totals = focus === 'cost'
      ? [['cost', b.employerCost, true], ['grossPay', b.gross], ['netPay', b.net]]
      : [['netPay', b.net, true], ['grossPay', b.gross], ['cost', b.employerCost]];
    var html = '<div class="totals">' + totals.map(function (x) {
      return '<div class="total' + (x[2] ? ' main' : '') + '"><span>' + t(x[0]) + '</span><strong>' + fmt(x[1], cur) + '</strong></div>';
    }).join('') + '</div>';
    if (b.minimum && b.minimum.below) {
      html += '<p class="alert">' + esc(t('below', { min: fmt(b.minimum.monthly, b.minimum.currency) })) + '</p>';
    }
    html += '<div class="tablewrap"><table class="lines">' +
      '<tr><th>' + t('earnings') + '</th><th class="num">' + cur + '</th></tr>' + rows('EARNING') +
      '<tr class="sum"><td>' + t('grossPay') + '</td><td class="num">' + fmt(b.gross) + '</td></tr>' +
      '<tr><th>' + t('deductions') + '</th><th></th></tr>' + rows('DEDUCTION') +
      '<tr class="sum"><td>' + t('netPay') + '</td><td class="num">' + fmt(b.net) + '</td></tr>' +
      '<tr><th>' + t('charges') + '</th><th></th></tr>' + rows('EMPLOYER_CHARGE') +
      '<tr class="sum"><td>' + t('cost') + '</td><td class="num">' + fmt(b.employerCost) + '</td></tr>' +
      '</table></div>';
    var d = new Date(b.ratesOn + 'T00:00:00').toLocaleDateString(fr ? 'fr-FR' : 'en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
    var info = t('ratesOn', { d: d });
    if (b.usdRate && cur === 'USD') {
      info += ' ' + t('rateUsd', { r: fmt(b.usdRate), cur: b.localCurrency, d: b.usdRateDate });
    }
    html += '<p class="note">' + esc(info) + '</p>';
    html += '<details><summary class="note" style="cursor:pointer">' + t('sources') + '</summary><ul class="sources">' +
      b.sources.map(function (s) {
        var name = SOURCES[s.code] ? SOURCES[s.code][fr ? 0 : 1] : s.code;
        var v = PERCENT.test(s.code) ? fmt(s.value) + ' %' : fmt(s.value);
        return '<li>' + esc(name) + ' : ' + v + (s.note ? ' (' + esc(s.note) + ')' : '') + '</li>';
      }).join('') + '</ul></details>';
    html += '<div class="cta"><a class="btn primary" href="' + H.app + '/trial?country=' + b.country + '">' + t('trial') + '</a>' +
      '<span class="note">' + t('trialHelp') + '</span></div>';
    html += '<form class="mail" id="calc-mail" novalidate><strong>' + t('mailTitle') + '</strong>' +
      '<div class="row2"><label class="field">' + t('email') + '<input id="calc-email" type="email" autocomplete="email" required></label>' +
      '<label class="field">' + t('company') + '<input id="calc-company" autocomplete="organization"></label>' +
      '<label class="field">' + t('phone') + '<input id="calc-phone" type="tel" autocomplete="tel"></label>' +
      '<label class="field">' + t('size') + '<select id="calc-size"><option>1-50</option><option>51-300</option><option>301-1000</option><option>1000+</option></select></label></div>' +
      '<label class="hp" aria-hidden="true">Website<input id="calc-website" tabindex="-1" autocomplete="off"></label>' +
      '<div class="cta"><button class="btn" type="submit" id="calc-send">' + t('send') + '</button><span class="note" id="calc-mail-msg">' + t('mailNote') + '</span></div></form>';
    el('result').innerHTML = html;
    el('mail').addEventListener('submit', function (e) { e.preventDefault(); sendMail(); });
  }

  function sendMail() {
    var email = el('email').value.trim();
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) { el('email').focus(); return; }
    var p = state.params, send = el('send');
    var calc = { country: p.country, mode: p.mode, amount: Number(p.amount), currency: p.currency,
      housing: p.housing ? Number(p.housing) : null, transport: p.transport ? Number(p.transport) : null,
      familyAllowances: p.familyAllowances ? Number(p.familyAllowances) : null, bonus: p.bonus ? Number(p.bonus) : null,
      children: Number(p.children || 0), maritalStatus: p.maritalStatus, spouseInCharge: !!p.spouseInCharge,
      employees: p.employees ? Number(p.employees) : null, expatriate: !!p.expatriate };
    send.disabled = true;
    fetch(H.api + '/api/public/calculator/leads', { method: 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email, companyName: el('company').value, phone: el('phone').value, employees: el('size').value,
        language: H.lang(), calculation: calc, website: el('website').value }) })
      .then(function (r) { if (!r.ok) { throw new Error(); } el('mail-msg').textContent = t('sent'); })
      .catch(function () { el('mail-msg').textContent = t('offline'); send.disabled = false; });
  }

  H.onLang(function () { if (el('form')) { state.ask = read(); } render(); });
  render();
  calculate();
})();
