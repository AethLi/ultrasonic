/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.service.parser;

import android.content.Context;

import org.moire.ultrasonic.R;
import org.moire.ultrasonic.domain.Playlist;
import org.moire.ultrasonic.domain.PodcastsChannel;
import org.moire.ultrasonic.util.ProgressListener;
import org.moire.ultrasonic.view.PlaylistAdapter;
import org.xmlpull.v1.XmlPullParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Sindre Mehus
 */
public class PodcastsChannelsParser extends AbstractParser
{

	public PodcastsChannelsParser(Context context)
	{
		super(context);
	}

	public List<PodcastsChannel> parse(Reader reader, ProgressListener progressListener) throws Exception
	{

		updateProgress(progressListener, R.string.parser_reading);
		init(reader);

		List<PodcastsChannel> result = new ArrayList<PodcastsChannel>();
		int eventType;
		do
		{
			eventType = nextParseEvent();
			if (eventType == XmlPullParser.START_TAG)
			{
				String tag = getElementName();
				if ("channel".equals(tag))
				{
					String id = get("id");
					String title = get("title");
					String url = get("url");
					String description = get("description");
					String status = get("status");
					result.add(new PodcastsChannel(id,title, url,description,status));
				}
				else if ("error".equals(tag))
				{
					handleError();
				}
			}
		} while (eventType != XmlPullParser.END_DOCUMENT);

		validate();
		updateProgress(progressListener, R.string.parser_reading_done);

		return result;
	}

}