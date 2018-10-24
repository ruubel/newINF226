package org.hisp.dhis.webapi.utils;

/*
 * Copyright (c) 2004-2018, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Date;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.StringUtils;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.dxf2.webmessage.WebMessageUtils;
import org.hisp.dhis.fileresource.FileResource;
import org.hisp.dhis.fileresource.FileResourceDomain;
import org.hisp.dhis.fileresource.FileResourceService;
import org.hisp.dhis.user.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;

/**
 * @author Lars Helge Overland
 */
public class FileResourceUtils
{
    @Autowired
    private FileResourceService fileResourceService;

    @Autowired
    private CurrentUserService currentUserService;

    /**
     * Transfers the given multipart file content to a local temporary file.
     *  
     * @param multipartFile the multipart file.
     * @return a temporary local file.
     * @throws IOException if the file content could not be transferred.
     */
    public static File toTempFile( MultipartFile multipartFile )
        throws IOException
    {
        File tmpFile = Files.createTempFile( "org.hisp.dhis", ".tmp" ).toFile();
        tmpFile.deleteOnExit();
        multipartFile.transferTo( tmpFile );
        return tmpFile;
    }

    /**
     * Indicates whether the content type represented by the given string
     * is a valid, known content type.
     * 
     * @param contentType the content type string.
     * @return true if the content is valid, false if not.
     */
    public static boolean isValidContentType( String contentType )
    {
        try
        {
            MimeTypeUtils.parseMimeType( contentType );
        }
        catch ( InvalidMimeTypeException ignored )
        {
            return false;
        }

        return true;
    }

    public void configureFileResourceResponse( HttpServletResponse response, FileResource fileResource )
        throws WebMessageException
    {
        ByteSource content = fileResourceService.getFileResourceContent( fileResource );

        if ( content == null )
        {
            throw new WebMessageException(
                WebMessageUtils.notFound( "The referenced file could not be found" ) );
        }

        // ---------------------------------------------------------------------
        // Attempt to build signed URL request for content and redirect
        // ---------------------------------------------------------------------

        URI signedGetUri = fileResourceService.getSignedGetFileResourceContentUri( fileResource.getUid() );

        if ( signedGetUri != null )
        {
            response.setStatus( HttpServletResponse.SC_TEMPORARY_REDIRECT );
            response.setHeader( HttpHeaders.LOCATION, signedGetUri.toASCIIString() );

            return;
        }

        // ---------------------------------------------------------------------
        // Build response and return
        // ---------------------------------------------------------------------

        response.setContentType( fileResource.getContentType() );
        response.setContentLength( new Long( fileResource.getContentLength() ).intValue() );
        response.setHeader( HttpHeaders.CONTENT_DISPOSITION, "filename=" + fileResource.getName() );

        // ---------------------------------------------------------------------
        // Request signing is not available, stream content back to client
        // ---------------------------------------------------------------------

        try (InputStream in = content.openStream())
        {
            IOUtils.copy( in, response.getOutputStream() );
        }
        catch ( IOException e )
        {
            throw new WebMessageException( WebMessageUtils.error( "Failed fetching the file from storage",
                "There was an exception when trying to fetch the file from the storage backend. " +
                    "Depending on the provider the root cause could be network or file system related." ) );
        }
    }

    public FileResource saveFile( MultipartFile file, FileResourceDomain domain )
        throws WebMessageException, IOException
    {
        String filename = StringUtils
            .defaultIfBlank( FilenameUtils.getName( file.getOriginalFilename() ), FileResource.DEFAULT_FILENAME );

        String contentType = file.getContentType();
        contentType = FileResourceUtils.isValidContentType( contentType ) ? contentType : FileResource.DEFAULT_CONTENT_TYPE;

        long contentLength = file.getSize();

        if ( contentLength <= 0 )
        {
            throw new WebMessageException( WebMessageUtils.conflict( "Could not read file or file is empty." ) );
        }

        ByteSource bytes = new MultipartFileByteSource( file );

        String contentMd5 = bytes.hash( Hashing.md5() ).toString();

        FileResource fileResource = new FileResource( filename, contentType, contentLength, contentMd5, FileResourceDomain.DATA_VALUE );
        fileResource.setAssigned( false );
        fileResource.setCreated( new Date() );
        fileResource.setUser( currentUserService.getCurrentUser() );
        fileResource.setDomain( domain );

        File tmpFile = toTempFile( file );

        String uid = fileResourceService.saveFileResource( fileResource, tmpFile );

        if ( uid == null )
        {
            throw new WebMessageException( WebMessageUtils.error( "Saving the file failed." ) );
        }

        return fileResource;
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private class MultipartFileByteSource
        extends ByteSource
    {
        private MultipartFile file;

        public MultipartFileByteSource( MultipartFile file )
        {
            this.file = file;
        }

        @Override
        public InputStream openStream() throws IOException
        {
            try
            {
                return file.getInputStream();
            }
            catch ( IOException ioe )
            {
                return new NullInputStream( 0 );
            }
        }
    }
}
