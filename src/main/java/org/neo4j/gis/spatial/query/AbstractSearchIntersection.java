/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.query;

import org.neo4j.gis.spatial.AbstractSearch;
import org.neo4j.graphdb.Node;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;


/**
 * @author Davide Savazzi
 */
public abstract class AbstractSearchIntersection extends AbstractSearch {

	public AbstractSearchIntersection(Geometry other) {
		this.other = other;
	}

	public boolean needsToVisit(Envelope indexNodeEnvelope) {
		return indexNodeEnvelope.intersects(other.getEnvelopeInternal());
	}
	
	public final void onIndexReference(Node geomNode) {	
		double[] geomEnvelope = getEnvelope(geomNode);
		if (geomEnvelope.intersects(other.getEnvelopeInternal())) {
			onEnvelopeIntersection(geomNode, geomEnvelope);
		}
	}
	
	protected abstract void onEnvelopeIntersection(Node geomNode, Envelope geomEnvelope);
	
	protected Geometry other;

	public String toString() {
		return "SearchIntersection[" + other.getEnvelopeInternal() + "]";
	}

}