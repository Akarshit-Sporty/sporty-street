#!/usr/bin/env python3
"""Refresh bundled Commons portraits and attribution; never runs during app startup/build."""
import concurrent.futures
import hashlib
import html
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOGUE = ROOT / 'src/main/resources/seed/football-2024-25.json'
OUTPUT = ROOT / 'src/main/resources/static/media/players'
HEADERS = {'User-Agent': 'SportyStreetHackathon/1.0 (local football prototype; asset attribution retained)'}


def fetch(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=40) as response:
                return response.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def query(host, **parameters):
    return json.loads(fetch('https://' + host + '/w/api.php?' + urllib.parse.urlencode(
        dict(action='query', format='json', **parameters))))['query']


def plain(value):
    return html.unescape(re.sub('<[^>]+>', '', value)).strip()


def main():
    data = json.loads(CATALOGUE.read_text())
    players = [p for club in data['clubs'] for p in club['players']]
    files = {}
    for start in range(0, len(players), 30):
        batch = players[start:start + 30]
        result = query('en.wikipedia.org', prop='pageimages', piprop='name', redirects=1,
                       titles='|'.join(p['wikipediaTitle'] for p in batch))
        aliases = {p['from']: p['to'] for key in ('normalized', 'redirects') for p in result.get(key, [])}
        pages = {p['title']: p for p in result['pages'].values()}
        for player in batch:
            title = player['wikipediaTitle']
            while title in aliases:
                title = aliases[title]
            files[player['slug']] = 'File:' + pages[title]['pageimage'].replace('_', ' ')
    info = {}
    titles = list(set(files.values()))
    for start in range(0, len(titles), 30):
        result = query('commons.wikimedia.org', prop='imageinfo', iiprop='url|extmetadata|mime',
                       iiurlwidth=320, titles='|'.join(titles[start:start + 30]))
        info.update({p['title']: p['imageinfo'][0] for p in result['pages'].values()})
    OUTPUT.mkdir(parents=True, exist_ok=True)

    def download(player):
        image = info[files[player['slug']]]
        metadata = image['extmetadata']
        licence = metadata.get('LicenseShortName', {}).get('value', '')
        if not any(token in licence.lower() for token in ('cc by', 'cc0', 'public domain')):
            raise ValueError('Review licence for ' + player['name'] + ': ' + licence)
        extension = {'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp'}[image['mime']]
        name = player['slug'] + extension
        content = fetch(image.get('thumburl', image['url']))
        if not (content.startswith(b'\xff\xd8') or content.startswith(b'\x89PNG') or content.startswith(b'RIFF')):
            raise ValueError('Not an image: ' + name)
        player['imageUrl'] = '/media/players/' + name
        print(player['name'] + ': ' + licence, flush=True)
        credit = dict(player=player['name'], imageUrl=player['imageUrl'], sourcePage=image['descriptionurl'],
                    originalUrl=image['url'], downloadedUrl=image.get('thumburl', image['url']),
                    author=plain(metadata.get('Artist', {}).get('value', 'See source page')),
                    credit=plain(metadata.get('Credit', {}).get('value', '')),
                    license=licence, licenseUrl=metadata.get('LicenseUrl', {}).get('value', ''),
                    modification='Wikimedia 320px thumbnail; no additional edits',
                    sha256=hashlib.sha256(content).hexdigest())
        return name, content, credit

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        downloaded = list(pool.map(download, players))
    # A network/licence failure leaves the checked-in assets and credits untouched.
    credits = [credit for _, _, credit in downloaded]
    for name, content, _ in downloaded:
        (OUTPUT / name).write_bytes(content)
    (OUTPUT / 'credits.json').write_text(json.dumps(credits, ensure_ascii=False, indent=2) + '\n')
    CATALOGUE.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print('Bundled ' + str(len(credits)) + ' portraits with attribution.')


if __name__ == '__main__':
    main()
