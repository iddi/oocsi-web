;function oocsiGraphVis(element, oocsiWS) {

    const container = document.querySelector(element);
    if(!container) { return; }

    let filterExpression = '';

    const svg = d3.select(element).append('svg')
            .attr('class', 'graph')
            .attr('width', container.clientWidth)
            .attr('height', container.clientHeight);
    const width = +svg.attr("width");
    const height = +svg.attr("height");

    const simulation = d3.forceSimulation()
        .force("link", d3.forceLink()
            .id(d => d.id)
            .distance(120)
            .strength(d => d.strength ? d.strength : 0)
        )
        .force("charge", d3.forceManyBody().strength(-50))
        .force("center", d3.forceCenter(width / 2, height / 2));

    const centerNode = { id: 'center', type: 'server', label: "", value: 2 };
    let nodes = [centerNode];
    let nodesIndex = 1;
    let links = [];
    let linksGraph = svg.append("g")
    let nodesGraph = svg.append("g")
    let labelsGraph = svg.append("g")

    const update = () => {
        const linkSelection = linksGraph.selectAll(".link")
            .data(links)
            .join("line")
            .attr("class", d => {
                if (d.type === 'client') {
                    return "link client";
                } else if (d.type === 'channel') {
                    return "link channel";
                } else if (d.type === 'subscription') {
                    return "link subscription";
                } else {
                    return "link";
                }
            })
            .attr("opacity", d => {
                if(isLinkFiltered(d)) {
                    return d.ttl < +Date.now() ? 0.5 : 1;
                } else {
                    return 0;
                }
            })
            // .attr("opacity", d => d.ttl < +Date.now() ? 0.5 : 1);

        const nodeSelection = nodesGraph.selectAll(".node")
            .data(nodes, d => d.id)
            .attr("opacity", d => {
                if(isNodeFiltered(d)) {
                    return 1;
                } else {
                    return 0;
                }
            })
            .join(
                enter => enter.append("circle")
                    .attr("class", d => "node " + d.type)
                    .attr("r", d => 5 * d.value)
                    .call(d3.drag()
                        .on("start", dragstarted)
                        .on("drag", dragged)
                        .on("end", dragended)),
                update => update,
                exit => exit.remove()
            );

        // Add labels for each node
        const labelSelection = labelsGraph.selectAll(".label")
            .data(nodes, d => d.id)
            .attr("opacity", d => {
                if(isNodeFiltered(d)) {
                    return 1;
                } else {
                    return 0;
                }
            })
            .join(
                enter => enter.append("text")
                    .attr("class", "label")
                    .text(d => d.label)
                    .attr("dx", 10)
                    .attr("dy", 3),
                update => update,
                exit => exit.remove()
            );

        simulation.nodes(nodes)
            .on("tick", () => {
                linkSelection
                    .attr("x1", d => d.source.x)
                    .attr("y1", d => d.source.y)
                    .attr("x2", d => d.target.x)
                    .attr("y2", d => d.target.y);

                nodeSelection
                    .attr("cx", d => d.x)
                    .attr("cy", d => d.y);

                labelSelection
                    .attr("x", d => d.x)
                    .attr("y", d => d.y);
            });

        simulation.force("link").links(links);
        simulation.alpha(1).restart();
    };

    // test if edge is visible
    function isLinkFiltered(d) {
        return filterExpression.length == 0 || (isNodeFiltered(d.source) && isNodeFiltered(d.target))
    }

    // test if node is visible
    function isNodeFiltered(d) {
        return filterExpression.length == 0 || (d.type !== 'client' || d.chains.some(c => c.toLowerCase().includes(filterExpression)))
    }

    function dragstarted(event, d) {
        if (!event.active) simulation.alphaTarget(0.3).restart();
        d.fx = d.x;
        d.fy = d.y;
    }

    function dragged(event, d) {
        d.fx = event.x;
        d.fy = event.y;
    }

    function dragended(event, d) {
        if (!event.active) simulation.alphaTarget(0);
        d.fx = null;
        d.fy = null;
    }

    function addClient(sourceName) {
        let components = sourceName.split('/').filter(c => c.length > 0)
        let previousNode = centerNode;
        let qualName = 'clients'
        for(index in components) {
            let comp = components[index]
            qualName += '/' + comp
            target = previousNode;

            if(target !== centerNode) {
                target.chains.push(sourceName)
            }

            let node = findNode(qualName)
            if(!node) {
                node = newNode(qualName, comp, 1, 'client', sourceName);
                newLink(node, target, 'client', 1)
                nodes.push(node);
                update();
            } else {
                // check whether the link exists already
                let existingLink = links.find(link => link.source === node && link.target === target)
                if(!existingLink) {
                    newLink(node, target, 'client', 1)
                    update();
                }
            }
            previousNode = node;
        }
    }

    function addChannel(sourceName) {
        let components = sourceName.split('/').filter(c => c.length > 0)
        let previousNode = centerNode;
        let qualName = 'channels'
        for(index in components) {
            let comp = components[index]
            qualName += '/' + comp
            target = previousNode;

            let node = findNode(qualName)
            if(!node) {
                node = newNode(qualName, comp, 1, 'channel');
                newLink(node, target, 'channel', target === centerNode ? 1 : 0.2)
                nodes.push(node);
                update();
            } else {
                // check whether the link exists already
                let existingLink = links.find(link => link.source === node && link.target === target)
                if(!existingLink) {
                    newLink(node, target, 'channel', 0.5)
                    update();
                }
            }
            previousNode = node;
        }
    }

    function newNode(qualName, label, value, type, chain) {
        return {
            id: nodesIndex++, 
            qualName: qualName, 
            label: label, 
            value: 1,
            type: type,
            chains: [chain]
        }
    }

    function findNode(qualName) {
        return nodes.find(node => node.qualName === qualName)
    }

    function newLink(source, target, type, strength) {
        links.push({
            id: nodesIndex++, 
            source: source, 
            target: target, 
            type: type, 
            strength: strength, 
            ttl: +Date.now() + 30000
        });
    }

    function findLink(source, target) {
        return links.find(link => (link.source === source && link.target === target) 
            || (link.source === target && link.target === source));
    }

    function sendMessage(client, channel, subscribers = []) {
        subscribers = Array.isArray(subscribers) ? subscribers : [subscribers]
        reqsCounter++;
        let source = findNode('clients/' + client)
        if(!source) {
            addClient(client)
            source = findNode('clients/' + client)
        }

        let target = findNode('channels/' + channel)
        if(!target) {
            addChannel(channel)
            target = findNode('channels/' + channel)
        }

        const path = findPath(source, target)
        animateDot(path, client.includes('tool') && client.includes('gen') ? ' generated' : '');

        setTimeout(() => {
            subscribers.filter(s => s.length > 2).forEach(s => {
                let sNode = findNode('clients/' + s)
                if(!sNode) {
                    // add subscription
                    addClient(s)
                    sNode = findNode('clients/' + s)
                }
                let link = links.find(link => link.source === target && link.target === sNode)
                if(!link) {
                    newLink(target, sNode, 'subscription', 0)
                    // links.push({ source: target, target: sNode, type: 'subscription', strength: 0 });
                    update();
                }

                animateDot([target, sNode], ' shadow');
            })
        }, (path.length-1) * 800)
    }

    function intRand(limit = 3) {
        return Math.round(Math.random() * limit)
    }

    function findPath(source, target) {
        const queue = [source];
        const visited = new Set();
        const parentMap = new Map();
        visited.add(source.id);

        while (queue.length > 0) {
            const current = queue.shift();

            if (current.id === target.id) {
                const path = [];
                let step = target;
                while (step) {
                    path.unshift(step);
                    step = parentMap.get(step.id);
                }
                return path;
            }

            const neighbors = links
                .filter(link => link.type !== 'subscription')
                .filter(link => link.source.id === current.id || link.target.id === current.id)
                .map(link => (link.source.id === current.id ? link.target : link.source));

            for (const neighbor of neighbors) {
                if (!visited.has(neighbor.id)) {
                    visited.add(neighbor.id);
                    parentMap.set(neighbor.id, current);
                    queue.push(neighbor);
                }
            }
        }
        return []; // Return an empty path if no path is found
    }

    function animateDot(path, additionalClass='') {
        if (path.length < 2) { return;}
        if(!path.every(d => isNodeFiltered(d))) { return; }

        const dot = svg.append("circle")
            .attr("class", "moving-dot" + additionalClass)
            .attr("r", 4)
            .attr("cx", path[0].x)
            .attr("cy", path[0].y);

        const duration = 800 * (path.length-1); // Duration of the animation in milliseconds
        const steps = 100; // Number of steps in the animation
        const interval = duration / (steps * (path.length - 1));

        for (let j = 0; j < path.length - 1; j++) {
            let link = findLink(path[j], path[j + 1]);
            if(link) {
                link.ttl = +Date.now() + 30000;
            }
            for (let i = 0; i <= steps; i++) {
                setTimeout(() => {
                    const t = i / steps; // Normalized time [0, 1]
                    const x = path[j].x + t * (path[j + 1].x - path[j].x);
                    const y = path[j].y + t * (path[j + 1].y - path[j].y);
                    dot.attr("cx", x).attr("cy", y);
                }, i * interval + j * duration/(path.length-1));
            }
        }

        // Remove the dot after animation
        setTimeout(() => {
            dot.remove();
        }, duration);
    }

    update(); // Initial render

    // ----------------------------------------------------------------------------------------------

    // connect to OOCSI and subscribe to all events
    OOCSI.connect(oocsiWS, 'OOCSI/tools/oocsiGraphVis_####');
    OOCSI.subscribe("OOCSI_events", e => {
        sendMessage(e.data['PUB'], e.data['CHANNEL'], e.data['SUB'])
    });

    // ----------------------------------------------------------------------------------------------

    // create and wire up the filter widget
    (() => {
        // don't render the widgets for small views
        if(!container || container.clientWidth < 960 || container.clientHeight < 400) { return; }

        d3.select(element).append('div')
            .attr('class', 'widget search')
            .html(`<span class="text">Filter clients:</span>
                <br>
                <input type="search">`)
        d3.select(element + " .search input").on("input", () => {
            filterExpression = d3.select(element + " .search input").property("value").toLowerCase();
            update()
        });
    })();

    // ----------------------------------------------------------------------------------------------

    // run sparklines
    let reqsCounter = 0;
    (() => {
        function buildSparkline(selector) {
            const svg = d3.select(selector + " .sparkline")
                .attr("width", 300)
                .attr("height", 60);

            let data = [0, 0, 0, 0];

            function drawSparkline(data) {
                const xScale = d3.scaleLinear()
                    .domain([0, data.length - 1])
                    .range([0, 3*(data.length-1)]);

                const yScale = d3.scaleLinear()
                    .domain([0, d3.max(data)])
                    .range([56, 4]);

                const line = d3.line()
                    .x((d, i) => xScale(i))
                    .y(d => yScale(d));

                svg.selectAll("*").remove(); // Clear previous content
                svg.append("path")
                    .datum(data)
                    .attr("fill", "none")
                    .attr("stroke", "black")
                    .attr("stroke-width", 1)
                    .attr("d", line);
            }

            // Initial draw
            drawSparkline(data);

            return (newDatum) => {
                while(data.length > 100) { data.shift(); }
                data.push(newDatum);
                drawSparkline(data);
                document.querySelector(selector + ' .value').innerText = newDatum;
            }; 
        }

        // don't render the widgets for small views
        if(!container || container.clientWidth < 960 || container.clientHeight < 400) { return; }

        d3.select(element).append('div')
            .attr('class', 'widget messages')
            .html(`<span class="text">Messages/s:</span>
            <span class="text value"></span>
            <svg class="sparkline"></svg>`)
        let messagesSparkline =  buildSparkline(element + ' .messages')

        d3.select(element).append('div')
            .attr('class', 'widget clients')
            .html(`<span class="text">Connected clients:</span>
            <span class="text value"></span>
            <svg class="sparkline"></svg>`)
        let clientsSparkline =  buildSparkline(element + ' .clients')

        d3.select(element).append('div')
            .attr('class', 'widget subscriptions')
            .html(`<span class="text">Subscriptions:</span>
            <span class="text value"></span>
            <svg class="sparkline"></svg>`)
        let subscriptionsSparkline =  buildSparkline(element + ' .subscriptions')

        setInterval(() => {
            messagesSparkline(reqsCounter);
            reqsCounter = 0;
            clientsSparkline(links.filter(l => l.type === 'client' && l.ttl > +Date.now()).length);
            subscriptionsSparkline(links.filter(l => l.type === 'subscription' && l.ttl > +Date.now()).length);
        }, 1000)
    })();
}