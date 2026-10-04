"""Local verification relay. Credentials resolve in native Hermes and never enter browser storage."""
import contextlib, json, sys
sys.path.insert(0, sys.argv[1])
try:
    with contextlib.redirect_stdout(sys.stderr):
        from hermes_cli.runtime_provider import resolve_runtime_provider
        runtime = resolve_runtime_provider(requested='custom')
    import httpx
    payload = json.loads(sys.stdin.buffer.read(1048577))
    if payload.get('model') != sys.argv[2]:
        raise ValueError('model differs from selected qualification route')
    url = runtime['base_url'].rstrip('/') + '/chat/completions'
    with httpx.stream('POST', url, headers={'Authorization': 'Bearer ' + runtime['api_key']},
                      json=payload, timeout=httpx.Timeout(120, connect=15), follow_redirects=False) as response:
        metadata = {'status': response.status_code, 'contentType': response.headers.get('content-type', 'application/json')}
        sys.stdout.buffer.write((json.dumps(metadata) + '\n').encode())
        sys.stdout.buffer.flush()
        total = 0
        for chunk in response.iter_bytes():
            total += len(chunk)
            if total > 16 * 1024 * 1024:
                raise ValueError('upstream stream exceeds relay byte limit')
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
except BaseException as error:
    # Do not print request objects, config, exceptions carrying URLs/credentials, or headers.
    sys.stderr.write(type(error).__name__ + '\n')
    sys.exit(1)
