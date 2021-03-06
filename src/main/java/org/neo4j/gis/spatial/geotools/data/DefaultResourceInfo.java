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
package org.neo4j.gis.spatial.geotools.data;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import org.geotools.data.ResourceInfo;
import org.geotools.feature.FeatureTypes;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.opengis.referencing.crs.CoordinateReferenceSystem;


/**
 * ResourceInfo implementation.
 * 
 * @author Davide Savazzi
 */
public class DefaultResourceInfo implements ResourceInfo {

	// Constructor
	
	public DefaultResourceInfo(String name, CoordinateReferenceSystem crs, ReferencedEnvelope bbox) {
		this.name = name;
		this.crs = crs;
		this.bbox = bbox;
	}
	
	
	// Public methods
	
	public String getName() {
		return name;
	}

	public String getTitle() {
		return name;
	}			
	
	public String getDescription() {
		return description;
	}

	public Set<String> getKeywords() {
        return keywords;
	}
	
	public URI getSchema() {
        return FeatureTypes.DEFAULT_NAMESPACE;
	}

	public CoordinateReferenceSystem getCRS() {
		return crs;
	}    		
	
	public ReferencedEnvelope getBounds() {
		return bbox;
	}

	
	// Attributes
	
	private String name;
	private String description = "";
	private Set<String> keywords = new HashSet<String>();
	private CoordinateReferenceSystem crs;
	private ReferencedEnvelope bbox;
}