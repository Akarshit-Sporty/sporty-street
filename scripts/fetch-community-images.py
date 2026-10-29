#!/usr/bin/env python3
"""Download bundled default avatars and league artwork, retaining Commons attribution."""
import concurrent.futures
import hashlib
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('player_images', ROOT / 'scripts/fetch-player-images.py')
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)
ASSETS = [
    ('avatars', 'alice', 'Ragdoll'),
    ('avatars', 'bob', 'Keanu Reeves'),
    ('avatars', 'carol', 'British Shorthair'),
    ('avatars', 'dave', 'Ryan Reynolds'),
    ('avatars', 'eve', 'Emma Watson'),
    ('avatars', 'frank', 'Golden Retriever'),
    ('avatars', 'grace', 'Zendaya'),
    ('avatars', 'henry', 'Maine Coon'),
    ('avatars', 'mr-pundit', 'Idris Elba'),
    ('avatars', 'kay-football', 'Pedro Pascal'),
    ('avatars', 'samba-tactics', 'Bengal cat'),
    ('avatars', 'abi-ballon', 'Siamese cat'),
    ('leagues', 'lagos-ballers', 'Lagos'),
    ('leagues', 'office-five', 'Association football'),
    ('leagues', 'mr-pundit', 'Wembley Stadium'),
    ('leagues', 'kay-football', 'Anfield'),
    ('leagues', 'samba-tactics', 'Maracanã Stadium'),
    ('leagues', 'abi-ballon', 'Camp Nou'),
]


def main():
    result = source.query('en.wikipedia.org', prop='pageimages', piprop='name', redirects=1,
                          titles='|'.join(a[2] for a in ASSETS))
    aliases = {p['from']: p['to'] for key in ('normalized', 'redirects') for p in result.get(key, [])}
    pages = {p['title']: p for p in result['pages'].values()}
    files = {}
    for kind, key, title in ASSETS:
        while title in aliases:
            title = aliases[title]
        if 'pageimage' not in pages[title]:
            raise ValueError('No default image: ' + title)
        files[(kind, key)] = 'File:' + pages[title]['pageimage'].replace('_', ' ')
    result = source.query('commons.wikimedia.org', prop='imageinfo', iiprop='url|extmetadata|mime',
                          iiurlwidth=640, titles='|'.join(files.values()))
    info = {p['title']: p['imageinfo'][0] for p in result['pages'].values()}

    def download(asset):
        kind, key, title = asset
        image = info[files[(kind, key)]]
        meta = image['extmetadata']
        licence = meta.get('LicenseShortName', {}).get('value', '')
        if not any(t in licence.lower() for t in ('cc by', 'cc0', 'public domain')):
            raise ValueError('Review licence: ' + title + ' ' + licence)
        # API fixtures and seeded user avatars use stable .jpg paths.
        if image['mime'] != 'image/jpeg':
            raise ValueError('Choose a JPEG source to preserve the existing URL: ' + title)
        extension = '.jpg'
        content = source.fetch(image.get('thumburl', image['url']))
        if not (content.startswith(b'\xff\xd8') or content.startswith(b'\x89PNG') or content.startswith(b'RIFF')):
            raise ValueError('Invalid image: ' + title)
        path = '/media/' + kind + '/' + key + extension
        credit = dict(key=key, imageUrl=path, subject=title,
                      sourcePage=image['descriptionurl'], originalUrl=image['url'],
                      author=source.plain(meta.get('Artist', {}).get('value', 'See source page')),
                      license=licence, licenseUrl=meta.get('LicenseUrl', {}).get('value', ''),
                      modification='Wikimedia 640px thumbnail; no additional edits',
                      sha256=hashlib.sha256(content).hexdigest())
        print(key + ': ' + licence, flush=True)
        return kind, path, content, credit

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        downloaded = list(pool.map(download, ASSETS))
    root = ROOT / 'src/main/resources/static'
    for kind, path, content, credit in downloaded:
        output = root / path.lstrip('/')
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(content)
    for kind in ('avatars', 'leagues'):
        credits = [credit for k, _, _, credit in downloaded if k == kind]
        (root / 'media' / kind / 'credits.json').write_text(json.dumps(credits,ensure_ascii=False,indent=2)+'\n')
    print('Downloaded', len(downloaded), 'images')


if __name__ == '__main__':
    main()
