let liveSiegeApiData = {}; // Cache for data from /api/livesiegeinfo

async function fetchData(url) {
    try {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        return await response.json();
    } catch (error) {
        console.error("Failed to fetch data from " + url + ":", error);
        return []; 
    }
}

async function loadLiveSiegeInfo() {
    try {
        const data = await fetchData('/api/livesiegeinfo');
        const newLiveData = {};
        data.forEach(liveSiege => {
            if (liveSiege.id) { 
                newLiveData[liveSiege.id] = liveSiege;
            } else if (liveSiege.townName) { 
                newLiveData[liveSiege.townName.toLowerCase()] = liveSiege;
            }
        });
        liveSiegeApiData = newLiveData;
    } catch (error) {
        console.error("Error updating live siege API data:", error);
        liveSiegeApiData = {}; 
    }
}

function renderSiegeCard(siege, containerId) { // siege is from /api/siegestats/active
    const container = document.getElementById(containerId);
    if (!container) return;

    const card = document.createElement('div');
    card.className = 'siege-card';
    card.dataset.siegeId = siege.siegeId; 
    card.dataset.townName = siege.townName.toLowerCase(); 

    let titleText = siege.townName; 

    const startTime = new Date(siege.startTimeMillis);
    const timeString = startTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: true });
    const dateString = startTime.toLocaleDateString([], { month: 'short', day: '2-digit', year: 'numeric' });
    
    let cardContent = `
        <h3>${titleText}</h3>
        <p class="siege-meta-info">${timeString} - ${dateString}</p>
    `;

    const liveDataForThisSiege = liveSiegeApiData[siege.siegeId];
    let attackerNameDisplay = "N/A";
    let defenderNameDisplay = siege.townName; 

    if (liveDataForThisSiege) {
        let winningSideText = liveDataForThisSiege.currentlyWinning === 'ATTACKERS' ? 'Attackers' : 'Defenders';
        let winningSideClass = liveDataForThisSiege.currentlyWinning === 'ATTACKERS' ? 'winning-attackers' : 'winning-defenders';
        
        attackerNameDisplay = liveDataForThisSiege.attackingNationName || "N/A"; 
        defenderNameDisplay = liveDataForThisSiege.defendingPartyName || siege.townName; 

        cardContent += `
            <p class="siege-balance">Balance: <span class="stat-value">${liveDataForThisSiege.balance}</span></p>
            <p class="siege-winning-side"><span class="${winningSideClass}">${winningSideText} Winning</span></p>
        `;
    } else {
        cardContent += `
            <p class="siege-balance">Balance: <span class="stat-value">N/A</span></p>
            <p class="siege-winning-side">Winning: <span class="stat-value">N/A</span></p>
        `;
    }
    
    cardContent += `
        <div class="siege-participants-display">
            <div class="siege-participant-attacker">
                <span class="participant-label">Attackers</span>
                <span class="participant-name">${attackerNameDisplay}</span>
            </div>
            <div class="siege-participant-defender">
                <span class="participant-label">Defenders</span>
                <span class="participant-name">${defenderNameDisplay}</span>
            </div>
        </div>
    `;

    // Participants count from SiegeStats plugin data (all-time for the tracked siege)
    let totalPluginParticipants = "N/A";
    if (siege.participants && Array.isArray(siege.participants)) {
        totalPluginParticipants = siege.participants.length;
    }
    cardContent += `<p class="siege-total-participants">Participants: <span class="stat-value">${totalPluginParticipants}</span></p>`;
    
    card.innerHTML = cardContent;
    container.appendChild(card);
}

function loadActiveSieges() {
    fetchData('/api/siegestats/active').then(data => {
        const container = document.getElementById('active-sieges-list');
        if (!container) return;
        container.innerHTML = ''; 
        if (data.length === 0) {
            container.innerHTML = '<p>No active sieges being tracked by the plugin.</p>';
            return;
        }
        data.sort((a,b) => b.startTimeMillis - a.startTimeMillis);
        data.forEach(siege => renderSiegeCard(siege, 'active-sieges-list'));
    });
}

function loadPlayerStats() {
    fetchData('/api/playerstats').then(data => {
        const tableBody = document.getElementById('player-stats-table');
        if (!tableBody) return;
        tableBody.innerHTML = ''; 

        const sortBy = document.getElementById('playerSortDropdown').value;
        data.sort((a, b) => {
            switch (sortBy) {
                case 'kills': return b.totalKills - a.totalKills;
                case 'deaths': return b.totalDeaths - a.totalDeaths; 
                case 'assists': return b.totalAssists - a.totalAssists;
                case 'damage': return b.totalDamage - a.totalDamage;
                case 'kadar': 
                default:
                    const kadarA = (Number(a.totalKills) || 0) + (Number(a.totalAssists) || 0);
                    const kadarB = (Number(b.totalKills) || 0) + (Number(b.totalAssists) || 0);
                    return kadarB - kadarA;
            }
        });

        data.forEach(player => {
            const row = tableBody.insertRow();
            const nameCell = row.insertCell();
            const headImg = document.createElement('img');
            headImg.src = `https://mc-heads.net/avatar/${encodeURIComponent(player.lastKnownName)}/24`;
            headImg.alt = player.lastKnownName;
            headImg.className = 'player-head';
            headImg.onerror = function() { 
                this.src = 'https://mc-heads.net/avatar/Steve/24'; 
                this.alt = 'Default Player Head';
            };
            nameCell.appendChild(headImg);
            nameCell.appendChild(document.createTextNode(" " + player.lastKnownName));

            row.insertCell().textContent = player.totalKills;
            row.insertCell().textContent = player.totalDeaths;
            row.insertCell().textContent = player.totalAssists;
            row.insertCell().textContent = player.totalDamage.toFixed(1);
        });
    });
}

function filterContent() {
    const mainSearchInput = document.getElementById('mainSearch');
    if (!mainSearchInput) return;
    const filter = mainSearchInput.value.toLowerCase();

    const playerTable = document.getElementById("player-stats-table");
    if (playerTable) {
        const tr = playerTable.getElementsByTagName("tr");
        for (let i = 0; i < tr.length; i++) {
            const td = tr[i].getElementsByTagName("td")[0]; 
            if (td) {
                let txtValue = "";
                td.childNodes.forEach(node => {
                    if (node.nodeType === Node.TEXT_NODE) {
                        txtValue += node.textContent || node.innerText;
                    }
                });
                txtValue = txtValue.trim();
                if (txtValue.toLowerCase().indexOf(filter) > -1) {
                    tr[i].style.display = "";
                } else {
                    tr[i].style.display = "none";
                }
            }
        }
    }

    const activeSiegesList = document.getElementById("active-sieges-list");
    if (activeSiegesList) {
        const cards = activeSiegesList.getElementsByClassName("siege-card");
        for (let i = 0; i < cards.length; i++) {
            const card = cards[i];
            const townNameFromDataset = card.dataset.townName || ""; 
            
            if (townNameFromDataset.indexOf(filter) > -1) { 
                card.style.display = "";
            } else {
                card.style.display = "none";
            }
        }
    }
}

async function initialLoadAndUpdate() {
    await loadLiveSiegeInfo();
    loadActiveSieges();
    loadPlayerStats();
}

document.addEventListener('DOMContentLoaded', function() {
    initialLoadAndUpdate();

    setInterval(async () => {
        await loadLiveSiegeInfo();
        loadActiveSieges();
    }, 5 * 60 * 1000); 

    const playerSortDropdown = document.getElementById('playerSortDropdown');
    if (playerSortDropdown) {
        playerSortDropdown.addEventListener('change', loadPlayerStats);
    }

    const mainSearchInput = document.getElementById('mainSearch');
    if (mainSearchInput) {
        mainSearchInput.addEventListener('keyup', filterContent);
    }
});