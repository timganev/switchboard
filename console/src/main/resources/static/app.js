const form = document.getElementById('prompt-form');
const prompt = document.getElementById('prompt');
const send = document.getElementById('send');
const status = document.getElementById('status');
const result = document.getElementById('result');

const ROUTES = {
    CLOUD: 'Nothing sensitive was found, so the prompt went to the model as it is.',
    SURROGATE: 'Sensitive values went out as realistic fakes and came back as the real ones.',
    LOCAL: 'Kept in: only a local model saw this prompt.',
    BLOCK: 'Blocked: nothing was sent anywhere.',
};

document.querySelectorAll('.example').forEach(button => button.addEventListener('click', () => {
    prompt.value = button.dataset.prompt;
    form.requestSubmit();
}));

prompt.addEventListener('keydown', event => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        form.requestSubmit();
    }
});

form.addEventListener('submit', async event => {
    event.preventDefault();
    const text = prompt.value.trim();
    if (!text) {
        prompt.focus();
        return;
    }
    send.disabled = true;
    status.textContent = 'Asking the guardian and the gateway…';
    status.classList.remove('error');
    try {
        const response = await fetch('api/playground', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({prompt: text}),
        });
        const body = await response.json();
        if (!response.ok) {
            throw new Error(body.error || `The console answered ${response.status}`);
        }
        render(text, body);
        status.textContent = '';
    } catch (error) {
        status.textContent = error.message;
        status.classList.add('error');
    } finally {
        send.disabled = false;
    }
});

function render(text, run) {
    const realValues = run.swaps.map(swap => swap.value);
    const route = run.route || 'CLOUD';

    const badge = document.getElementById('route');
    badge.textContent = route;
    badge.className = `route ${route.toLowerCase()}`;
    document.getElementById('route-text').textContent = ROUTES[route] || '';
    const reasons = document.getElementById('reasons');
    reasons.replaceChildren(...run.reasons.map(reason => element('span', 'reason', `${reason.type} × ${reason.count}`)));
    document.getElementById('latency').textContent = `${run.latencyMs} ms through the gateway`;

    mark(document.getElementById('written'), text, realValues, 'real');

    const sent = document.getElementById('sent');
    const sentCaption = document.getElementById('sent-caption');
    if (run.sent == null) {
        sentCaption.textContent = 'The guardian stopped the request.';
        empty(sent, 'Nothing left.');
    } else if (route === 'LOCAL') {
        sentCaption.textContent = 'It stayed inside: only a local model received it.';
        mark(sent, run.sent, [], 'fake');
    } else if (route === 'SURROGATE') {
        sentCaption.textContent = 'The fakes are marked; the model never saw the real values.';
        mark(sent, run.sent, run.swaps.map(swap => swap.surrogate), 'fake');
    } else {
        sentCaption.textContent = 'Sent as it is.';
        mark(sent, run.sent, [], 'fake');
    }

    const answer = document.getElementById('answer');
    const answerCaption = document.getElementById('answer-caption');
    if (run.error) {
        answerCaption.textContent = `The gateway answered ${run.status}.`;
        answer.replaceChildren(element('span', 'error', run.error));
        answer.classList.remove('empty');
    } else {
        const findings = run.responseFindings ? ` The answer also held data the model made up: ${run.responseFindings}.` : '';
        answerCaption.textContent = (route === 'SURROGATE' ? 'The fakes were turned back into the real values.' : 'As the model wrote it.') + findings;
        mark(answer, run.answer || '', route === 'SURROGATE' ? realValues : [], 'real');
    }

    const swaps = document.getElementById('swaps');
    swaps.replaceChildren(...run.swaps.map(swap => {
        const row = document.createElement('tr');
        row.append(element('td', '', swap.type), element('td', '', swap.value), element('td', '', swap.surrogate));
        return row;
    }));
    document.getElementById('swaps-details').hidden = run.swaps.length === 0;

    result.hidden = false;
}

// Builds the text from nodes, never from HTML, so a prompt cannot inject markup.
function mark(container, text, terms, kind) {
    container.classList.remove('empty');
    const unique = [...new Set(terms.filter(Boolean))].sort((a, b) => b.length - a.length);
    if (unique.length === 0) {
        container.replaceChildren(document.createTextNode(text));
        return;
    }
    const pattern = new RegExp(`(${unique.map(escape).join('|')})`, 'g');
    const nodes = text.split(pattern).map((piece, index) =>
        index % 2 === 1 ? element('mark', kind, piece) : document.createTextNode(piece));
    container.replaceChildren(...nodes);
}

function empty(container, text) {
    container.replaceChildren(document.createTextNode(text));
    container.classList.add('empty');
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    node.textContent = text;
    return node;
}

function escape(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
