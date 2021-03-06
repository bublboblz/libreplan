/*
 * This file is part of LibrePlan
 *
 * Copyright (C) 2016 LibrePlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.libreplan.web.orders.files;

import org.apache.commons.io.FilenameUtils;
import org.hibernate.engine.jdbc.ReaderInputStream;
import org.libreplan.business.orders.entities.OrderElement;
import org.libreplan.business.orders.entities.OrderFile;

import org.libreplan.business.users.daos.IUserDAO;

import org.libreplan.web.common.IConfigurationModel;
import org.libreplan.web.common.IMessagesForUser;
import org.libreplan.web.common.Level;
import org.libreplan.web.common.Util;
import org.libreplan.web.common.MessagesForUser;
import org.libreplan.web.orders.IOrderElementModel;
import org.libreplan.web.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.util.GenericForwardComposer;
import org.zkoss.zul.Fileupload;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.ListitemRenderer;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Label;
import org.zkoss.zul.Filedownload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.text.SimpleDateFormat;
import java.util.Date;

import static org.libreplan.web.I18nHelper._;


/**
 * Controller for managing Order files.
 *
 * Created by
 * @author Vova Perebykivskiy <vova@libreplan-enterprise.com>
 * on 12.24.2015.
 */

public class OrderFilesController extends GenericForwardComposer {

    // TODO refactor Autowired?
    @Autowired
    IConfigurationModel configurationModel;

    @Autowired
    IUserDAO userDAO;

    private Component messagesContainer;

    private IMessagesForUser messages;

    private IOrderElementModel orderElementModel;

    private IOrderFileModel orderFileModel;

    private Listbox filesList;

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        // TODO resolve deprecated
        comp.setVariable("orderFilesController", this, true);
        messages = new MessagesForUser(messagesContainer);
    }

    public boolean isRepositoryExists() {
        configurationModel.init();

        File repositoryDirectory = null;
        if ( !(configurationModel.getRepositoryLocation() == null) )
            repositoryDirectory = new File(configurationModel.getRepositoryLocation());

        return repositoryDirectory != null && repositoryDirectory.exists();

    }

    public boolean isUploadButtonDisabled(){
        return !isRepositoryExists();
    }

    public ListitemRenderer getFilesRenderer(){
        return new ListitemRenderer() {
            @Override
            public void render(Listitem listitem, Object data) throws Exception {
                final OrderFile file = (OrderFile) data;

                Listcell nameCell = new Listcell();
                listitem.appendChild(nameCell);
                Label label = new Label(file.getName());

                label.addEventListener("onClick", new EventListener() {
                    @Override
                    public void onEvent(Event event) throws Exception {
                        configurationModel.init();
                        String projectCode = orderElementModel.getOrderElement().getCode();
                        String directory = configurationModel.getRepositoryLocation() + "orders" + "/" + projectCode;

                        File fileToDownload = new File(directory + "/" + file.getName() + "." + file.getType());
                        Filedownload.save(fileToDownload.getAbsoluteFile(), null);
                    }
                });

                label.setClass("label-highlight");
                label.setTooltiptext("Download file");
                nameCell.appendChild(label);


                Listcell typeCell = new Listcell();
                listitem.appendChild(typeCell);
                typeCell.appendChild(new Label(file.getType()));

                Listcell dateCell = new Listcell();
                listitem.appendChild(dateCell);
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
                dateCell.appendChild(new Label(sdf.format(file.getDate())));

                Listcell uploaderCell = new Listcell();
                listitem.appendChild(uploaderCell);
                uploaderCell.appendChild(new Label(file.getUploader().getLoginName()));

                Listcell operationsCell = new Listcell();
                listitem.appendChild(operationsCell);
                operationsCell.appendChild(Util.createRemoveButton(new EventListener() {
                    @Override
                    public void onEvent(Event event) throws Exception {
                        confirmRemove(file);
                    }
                }));

            }
        };
    }


    public void confirmRemove(OrderFile file){

        try {
            int status = Messagebox.show(_("Confirm deleting this file. Are you sure?"), _("Delete"),
                    Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION);
            if ( Messagebox.OK != status ) {
                return;
            }
        } catch (InterruptedException ignored) {}


        if ( isRepositoryExists() ) {

            String projectCode = orderElementModel.getOrderElement().getCode();
            configurationModel.init();
            String directory = configurationModel.getRepositoryLocation() + "orders" + "/" + projectCode;

            File fileToDelete = new File(directory + "/" + file.getName() + "." + file.getType());

            boolean deleted = fileToDelete.delete();

            if ( deleted ){
                orderFileModel.delete(file);

                messages.clearMessages();
                messages.showMessage(Level.INFO, "File successfully deleted");

                updateListbox();
            } else {
                messages.clearMessages();
                messages.showMessage(Level.ERROR, "Error while deleting");
            }

        } else {
            messages.clearMessages();
            messages.showMessage(Level.ERROR, "Repository not created");
        }


    }

    public void upload() {
        configurationModel.init();

        String directory;
        if ( isRepositoryExists() ){

            String projectCode = orderElementModel.getOrderElement().getCode();
            directory = configurationModel.getRepositoryLocation() + "orders" + "/" + projectCode;

            try {
                // Location of file: libreplan-webapp/src/main/webapp/planner/fileupload.zul
                Fileupload.setTemplate("fileupload.zul");


                Media media = Fileupload.get();

                File dir = new File(directory);
                String filename = media.getName();
                File file = new File(dir, filename);

                // By default Java do not create directories itself
                file.getParentFile().mkdirs();

                OutputStream outputStream = new FileOutputStream(file);

                InputStream inputStream = media.isBinary() ? (media.getStreamData()) :
                        (new ReaderInputStream(media.getReaderData()));

                if ( inputStream != null ){
                    byte[] buffer = new byte[1024];
                    for ( int count; (count = inputStream.read(buffer)) != -1; )
                        outputStream.write(buffer, 0, count);
                }

                outputStream.flush();
                outputStream.close();
                inputStream.close();


                orderFileModel.createNewFileObject();
                orderFileModel.setFileName(FilenameUtils.getBaseName(media.getName()));
                orderFileModel.setFileType(FilenameUtils.getExtension(media.getName()));
                orderFileModel.setUploadDate(new Date());
                orderFileModel.setUploader(userDAO.findByLoginName(SecurityUtils.getSessionUserLoginName()));
                orderFileModel.setParent(orderElementModel.getOrderElement());

                orderFileModel.confirmSave();


            } catch (Exception e){
                e.printStackTrace();
            }

            finally {
                updateListbox();
            }

        } else messages.showMessage(Level.ERROR, _("Please, make repository"));

    }

    /**
     * This method is a:
     * 1. setter for current opened {@link org.libreplan.business.orders.entities.Order}
     * 2. setter for model of ListBox of files
     *
     * The easiest way is to set a model in zul file, but its setter was invoking before
     * setter of current {@link org.libreplan.business.orders.entities.Order}
     */
    public void openWindow(IOrderElementModel orderElementModel) {
        setOrderElementModel(orderElementModel);

        if ( isRepositoryExists() )
            updateListbox();
    }

    /**
     * Listbox is updating after re set the model for it
     */
    private void updateListbox(){
        OrderElement currentOrder = orderElementModel.getOrderElement();
        filesList.setModel(new ListModelList(orderFileModel.findByParent(currentOrder)));
    }

    public IOrderElementModel getOrderElementModel() {
        return orderElementModel;
    }
    public void setOrderElementModel(IOrderElementModel orderElementModel) {
        this.orderElementModel = orderElementModel;
    }
}
