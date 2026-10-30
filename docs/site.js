"use strict";

const REPO = "aravpanwar/mc-diff";
const RELEASES_PAGE = `https://github.com/${REPO}/releases`;
const RELEASES_API = `https://api.github.com/repos/${REPO}/releases?per_page=39`;

// Jars are named mc-diff-<mod version>+<minecraft version>.jar, and the
// minecraft version in the name is the only thing that says which game a build is for.
const JAR_NAME = /^mc-diff-(.+?)\+(.+)\.jar$/;

const select = document.getElementById("version");
const button = document.getElementById("get");
const meta = document.getElementById("meta");
const notice = document.getElementById("notice");

let builds = [];

/*
 * Minecraft versions are not plain numbers ("1.21.1" sits below "26.2" and
 * "1.21.11" above "1.21.2"), so they are compared part by part, never as text.
 */
function compareVersions(a, b) {
	const left = a.split(".").map(Number);
	const right = b.split(".").map(Number);
	for (let i = 0; i < Math.max(left.length, right.length); i++) {
		const diff = (right[i] || 0) - (left[i] || 0);
		if (diff !== 0) {
			return diff;
		}
	}
	return 0;
}

/*
 * The API lists releases newest first, so the first build seen for a game
 * version wins. A stable build always beats a pre-release for the same version,
 * even a newer one, so people only get a pre-release when nothing else exists.
 */
function collectBuilds(releases) {
	const byVersion = new Map();
	for (const release of releases) {
		if (release.draft) {
			continue;
		}
		for (const asset of release.assets) {
			const match = JAR_NAME.exec(asset.name);
			if (!match) {
				continue;
			}
			const build = {
				mod: match[1],
				minecraft: match[2],
				url: asset.browser_download_url,
				size: asset.size,
				prerelease: release.prerelease,
				date: release.published_at,
				notes: release.html_url,
			};
			const existing = byVersion.get(build.minecraft);
			if (!existing || (existing.prerelease && !build.prerelease)) {
				byVersion.set(build.minecraft, build);
			}
		}
	}
	return [...byVersion.values()].sort((a, b) => compareVersions(a.minecraft, b.minecraft));
}

function show(build) {
	button.href = build.url;
	button.textContent = `Download mc-diff ${build.mod} for ${build.minecraft}`;
	button.removeAttribute("aria-disabled");

	const size = `${Math.max(1, Math.round(build.size / 1024))} KB`;
	const date = new Date(build.date).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
	meta.textContent = "";
	meta.append(`${size}, released ${date}, `);
	const link = document.createElement("a");
	link.href = build.notes;
	link.textContent = "release notes";
	meta.append(link);

	notice.hidden = !build.prerelease;
	notice.textContent = build.prerelease
		? "This is a pre-release and hasn't been tested as much as the other versions."
		: "";
}

function fallBack(reason) {
	select.replaceChildren(new Option("unavailable", ""));
	select.disabled = true;
	button.href = RELEASES_PAGE;
	button.textContent = "See all releases";
	meta.textContent = `${reason}. The releases page on GitHub has every version.`;
}

function ready(found) {
	builds = found;
	select.replaceChildren(...builds.map((build, index) => {
		const label = build.prerelease ? `${build.minecraft} (pre-release)` : build.minecraft;
		return new Option(label, String(index));
	}));

	// Newest game version that has a stable build; a pre-release is only the default when it is all there is.
	const stable = builds.findIndex((build) => !build.prerelease);
	select.value = String(stable === -1 ? 0 : stable);
	select.disabled = false;
	show(builds[Number(select.value)]);
}

select.addEventListener("change", () => show(builds[Number(select.value)]));

// An unsigned-in visitor gets 60 API calls an hour per address, so a failed call is expected now and then.
fetch(RELEASES_API, { headers: { Accept: "application/vnd.github+json" } })
	.then((response) => {
		if (!response.ok) {
			throw new Error(`github answered ${response.status}`);
		}
		return response.json();
	})
	.then((releases) => {
		const found = collectBuilds(releases);
		if (found.length === 0) {
			throw new Error("expected a release with an mc-diff jar, found none");
		}
		ready(found);
	})
	.catch((error) => {
		console.error(error);
		fallBack("couldn't load the release list");
	});
